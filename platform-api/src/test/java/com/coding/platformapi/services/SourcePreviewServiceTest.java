package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.platformapi.models.PipelineFileChangeDTO;
import com.coding.platformapi.models.PipelineFilePatchDTO;
import com.coding.platformapi.models.PipelineSourceRefDTO;
import com.coding.platformapi.outbound.OutboundProxy;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;

/**
 * 源码预览的**纯解析**部分（画布读取、sha 形状、基线解析）与**diff 本体**。
 *
 * <p>diff 那几条用本地仓库当 remote（{@code file://} URL）—— 不联网也能把
 * 「ls-remote → 浅取 → 两棵树对比」整条链路真跑一遍，而不是只测字符串。
 * 真正要碰外部 git host 的部分（私有仓库、代理、自建源禁 sha-fetch）只能在真环境验证。
 */
class SourcePreviewServiceTest {

    private final SourcePreviewService svc = newService(200);

    private static SourcePreviewService newService(int maxFiles) {
        return new SourcePreviewService(
                mock(CredentialService.class), mock(PlatformPipelineRunMapper.class),
                new OutboundProxy("", 0, ""), 30000, maxFiles);
    }

    @Test
    void gitNodesAreReadFromTheCanvasContractOnly() {
        String canvas = """
                {"nodes":[
                  {"id":"git-1","type":"git","config":{"repo":"https://gitlab.x/g/r.git","revision":"main","connectionId":"conn-1"}},
                  {"id":"sh-1","type":"shell","config":{"command":"echo hi"}},
                  {"id":"git-2","type":"git","config":{"repo":"https://github.com/a/b.git"}}
                ],"edges":[]}""";

        List<SourcePreviewService.GitNode> nodes = svc.gitNodes(canvas);

        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0).nodeId()).isEqualTo("git-1");
        assertThat(nodes.get(0).repo()).isEqualTo("https://gitlab.x/g/r.git");
        assertThat(nodes.get(0).revision()).isEqualTo("main");
        assertThat(nodes.get(0).connectionId()).isEqualTo("conn-1");
        // 留空的分支/连接要落成空串，而不是 null（下游直接拿去判断 isEmpty）
        assertThat(nodes.get(1).revision()).isEmpty();
        assertThat(nodes.get(1).connectionId()).isEmpty();
    }

    @Test
    void aGitNodeWithoutARepoFailsLoudlyInsteadOfReturningASha() {
        List<PipelineSourceRefDTO> out = svc.preview(null,
                "{\"nodes\":[{\"id\":\"git-1\",\"type\":\"git\",\"config\":{}}],\"edges\":[]}", "t1");

        assertThat(out).singleElement().satisfies(s -> {
            assertThat(s.sha()).isNull();
            assertThat(s.error()).contains("没有填仓库地址");
        });
    }

    @Test
    void onlyFullFortyCharacterHexCountsAsAPin() {
        assertThat(SourcePreviewService.isFullSha("3f2c1ab9d4e5f60718293a4b5c6d7e8f90123456")).isTrue();
        // 短 sha 只在界面上好看，**不能当 pin**：它不能唯一确定一个提交
        assertThat(SourcePreviewService.isFullSha("3f2c1ab")).isFalse();
        assertThat(SourcePreviewService.isFullSha("")).isFalse();
        assertThat(SourcePreviewService.isFullSha(null)).isFalse();
        assertThat(SourcePreviewService.isFullSha("ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ")).isFalse();
    }

    @Test
    void theBaselineIsTheShaRecordedForThatNodeInPreviousRuns() {
        String sourceJson = """
                [{"nodeId":"git-1","sha":"9a01c2f3d4e5f60718293a4b5c6d7e8f90123456"},
                 {"nodeId":"git-2","sha":null}]""";

        assertThat(SourcePreviewService.shaOfNode(sourceJson, "git-1"))
                .isEqualTo("9a01c2f3d4e5f60718293a4b5c6d7e8f90123456");
        assertThat(SourcePreviewService.shaOfNode(sourceJson, "git-2")).isNull();
        assertThat(SourcePreviewService.shaOfNode(sourceJson, "nope")).isNull();
        assertThat(SourcePreviewService.shaOfNode(null, "git-1")).isNull();
        // 老数据/手工改过：解析不了就当没有，不能让预览整个失败
        assertThat(SourcePreviewService.shaOfNode("{not json", "git-1")).isNull();
    }

    // ------------------------------------------------------------------ diff（本地仓库，真跑）

    @Test
    void previewWithDiffResolvesTheCommitAndListsItsChangedFiles() throws Exception {
        Path repo = Files.createTempDirectory("src-preview-repo-");
        try (Git git = openRepo(repo)) {
            writeAndCommit(git, "first", f -> {
                f.write("keep.txt", "a\nb\nc\n");
                f.write("gone.txt", "bye\n");
                f.write("old-name.txt", "rename me\n");
            });
            String head = writeAndCommit(git, "second", f -> {
                f.write("keep.txt", "a\nB\nc\nd\n");   // 改
                f.write("added.txt", "hello\nworld\n"); // 增
                f.delete("gone.txt");                   // 删
                f.rename("old-name.txt", "new-name.txt"); // 改名
            });

            String canvas = """
                    {"nodes":[{"id":"git-1","type":"git","config":{"repo":"%s","revision":"main"}}],
                     "edges":[]}""".formatted(repo.toUri());

            List<PipelineSourceRefDTO> out = svc.preview(null, canvas, "t1", true);

            assertThat(out).singleElement().satisfies(s -> {
                assertThat(s.error()).isNull();
                assertThat(s.warning()).isNull();
                assertThat(s.sha()).isEqualTo(head);
                assertThat(s.kind()).isEqualTo("branch");
                assertThat(s.subject()).isEqualTo("second");
                // 基线 = head 的父提交（这条流水线没有历史成功运行）
                assertThat(s.baseKind()).isEqualTo("parent");
                assertThat(s.baseSha()).isNotNull();

                assertThat(s.fileCount()).isEqualTo(4);
                assertThat(s.truncated()).isFalse();
                assertThat(s.files())
                        .extracting(PipelineFileChangeDTO::path, PipelineFileChangeDTO::status)
                        .containsExactlyInAnyOrder(
                                tuple("added.txt", "A"),
                                tuple("keep.txt", "M"),
                                tuple("gone.txt", "D"),
                                tuple("new-name.txt", "R"));
                // 改名要带上原路径 —— 否则界面把一次 mv 读成「删了一个又加了一个」
                assertThat(s.files()).filteredOn(f -> "R".equals(f.status()))
                        .singleElement()
                        .satisfies(f -> assertThat(f.oldPath()).isEqualTo("old-name.txt"));
                // 行数：added 2 + keep 改 1 行 + 加 1 行 = 4 增；keep 改 1 行 + gone 1 行 = 2 删
                assertThat(s.additions()).isEqualTo(4);
                assertThat(s.deletions()).isEqualTo(2);
            });
        } finally {
            deleteQuietly(repo);
        }
    }

    @Test
    void theLightPreviewNeverDownloadsObjectsSoThereIsNoCommitSubject() throws Exception {
        Path repo = Files.createTempDirectory("src-preview-repo-");
        try (Git git = openRepo(repo)) {
            String head = writeAndCommit(git, "first", f -> f.write("a.txt", "a\n"));
            String canvas = """
                    {"nodes":[{"id":"git-1","type":"git","config":{"repo":"%s"}}],"edges":[]}"""
                    .formatted(repo.toUri());

            List<PipelineSourceRefDTO> out = svc.preview(null, canvas, "t1");

            assertThat(out).singleElement().satisfies(s -> {
                assertThat(s.sha()).isEqualTo(head);
                // 打开触发框这一档只 ls-remote：标题要浅取整棵树才有，留到「查看变更」
                assertThat(s.subject()).isNull();
                assertThat(s.files()).isNull();
                assertThat(s.fileCount()).isZero();
                assertThat(s.warning()).isNull();
            });
        } finally {
            deleteQuietly(repo);
        }
    }

    @Test
    void aLongFileListIsCappedButTheTotalsStayHonest() throws Exception {
        Path repo = Files.createTempDirectory("src-preview-repo-");
        try (Git git = openRepo(repo)) {
            writeAndCommit(git, "first", f -> f.write("seed.txt", "seed\n"));
            writeAndCommit(git, "second", f -> {
                for (int i = 0; i < 5; i++) {
                    f.write("f" + i + ".txt", "line\n");
                }
            });
            String canvas = """
                    {"nodes":[{"id":"git-1","type":"git","config":{"repo":"%s"}}],"edges":[]}"""
                    .formatted(repo.toUri());

            List<PipelineSourceRefDTO> out = newService(2).preview(null, canvas, "t1", true);

            assertThat(out).singleElement().satisfies(s -> {
                // 列表被截断，但总数与行数是**全量**的 —— 界面要说的是「另有 3 个未显示」
                assertThat(s.files()).hasSize(2);
                assertThat(s.fileCount()).isEqualTo(5);
                assertThat(s.truncated()).isTrue();
                assertThat(s.additions()).isEqualTo(5);
            });
        } finally {
            deleteQuietly(repo);
        }
    }

    @Test
    void thePatchOfASingleFileOnlyContainsThatFile() throws Exception {
        Path repo = Files.createTempDirectory("src-preview-repo-");
        try (Git git = openRepo(repo)) {
            String base = writeAndCommit(git, "first", f -> {
                f.write("keep.txt", "a\nb\nc\n");
                f.write("other.txt", "untouched\n");
            });
            String head = writeAndCommit(git, "second", f -> {
                f.write("keep.txt", "a\nB\nc\nd\n");
                f.write("added.txt", "hello\n");
            });

            PipelineFilePatchDTO p = svc.patch(repo.toUri().toString(), "", head, base,
                    "keep.txt", "t1");

            assertThat(p.error()).isNull();
            assertThat(p.binary()).isFalse();
            assertThat(p.truncated()).isFalse();
            // unified diff：hunk 头 + 被删的那行 + 新增的两行
            assertThat(p.text()).contains("@@").contains("-b").contains("+B").contains("+d");
            // 只回这一个文件 —— 清单里点谁就只取谁
            assertThat(p.text()).doesNotContain("added.txt").doesNotContain("other.txt");
        } finally {
            deleteQuietly(repo);
        }
    }

    @Test
    void aPatchRequestWithoutFullShasIsRejectedInsteadOfGuessing() {
        PipelineFilePatchDTO p = svc.patch("https://example.com/r.git", "", "3f2c1ab", null,
                "a.txt", "t1");
        assertThat(p.text()).isNull();
        assertThat(p.error()).contains("sha 不完整");
    }

    // ------------------------------------------------------------------ 本地仓库小工具

    /** 一次提交的改动（写完就提交，省得每条用例重复 add/commit）。 */
    private interface Change {
        void apply(ChangeSet files) throws IOException;
    }

    private record ChangeSet(Path dir) {
        void write(String name, String content) throws IOException {
            Files.writeString(dir.resolve(name), content);
        }

        void delete(String name) throws IOException {
            Files.delete(dir.resolve(name));
        }

        void rename(String from, String to) throws IOException {
            Files.move(dir.resolve(from), dir.resolve(to));
        }
    }

    /**
     * 建一个本地仓库当 remote。
     *
     * <p>⚠️ 必须打开 {@code uploadpack.allowReachableSHA1InWant}：diff 要浅取**基线**那个提交，
     * 而它通常不是任何 ref 的尖端 —— git 默认拒绝「要一个没被广告出来的 sha」。
     * GitHub / GitLab 都是允许的（这也是线上能按 sha 取的前提）；自建源不开这一项时，
     * 预览会如实降级成「变更清单不可用」，运行时钉住也会失败并给可读文案。
     */
    private static Git openRepo(Path dir) throws Exception {
        Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call();
        var cfg = git.getRepository().getConfig();
        cfg.setBoolean("uploadpack", null, "allowReachableSHA1InWant", true);
        cfg.setBoolean("uploadpack", null, "allowAnySHA1InWant", true);
        cfg.save();
        return git;
    }

    private static String writeAndCommit(Git git, String message, Change change) throws Exception {
        change.apply(new ChangeSet(git.getRepository().getWorkTree().toPath()));
        // 先 add 新增/改动，再 add -u 收删除与改名 —— 两者缺一个都会漏进提交
        git.add().addFilepattern(".").call();
        git.add().setUpdate(true).addFilepattern(".").call();
        return git.commit()
                .setMessage(message)
                .setAuthor("tester", "tester@example.com")
                .setCommitter("tester", "tester@example.com")
                .call().getName();
    }

    private static void deleteQuietly(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // 临时目录，删不掉也不影响断言
                }
            });
        }
    }
}
