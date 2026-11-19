package com.coding.common.models.pipeline;

public record PipelineImageArtifact(String nodeName, String image, String digest) {
    public static PipelineImageArtifact from(String nodeName, String image, String digest) {
        if (image == null || digest == null) return null;
        image = image.strip();
        digest = digest.strip();
        int tag = image.lastIndexOf(':');
        if (tag <= image.lastIndexOf('/') || tag < 1 || tag == image.length() - 1
                || image.contains("@") || image.matches(".*\\s.*") || !digest.matches("sha256:[a-f0-9]{64}")) {
            return null;
        }
        return new PipelineImageArtifact(nodeName, image, digest);
    }
}
