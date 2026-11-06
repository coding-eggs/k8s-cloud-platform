package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.platformapi.models.AppExecutionLogView;
import org.springframework.stereotype.Service;

import java.util.List;

/** 发布过程日志的读：先过应用归属校验。 */
@Service
public class AppExecutionLogService {

    private final PlatformApplicationMapper apps;
    private final AppExecutionJournal journal;

    public AppExecutionLogService(PlatformApplicationMapper apps, AppExecutionJournal journal) {
        this.apps = apps;
        this.journal = journal;
    }

    public List<AppExecutionLogView> list(String appId, Integer releaseNo, String tenantId) {
        AppGuard.require(apps, appId, tenantId);
        if (releaseNo == null) {
            return List.of();
        }
        return journal.list(appId, releaseNo).stream()
                .map(l -> new AppExecutionLogView(l.getId(), l.getStage(), l.getLevel(), l.getMessage(), l.getCreatedAt()))
                .toList();
    }
}
