package com.coding.platformapi.models;

import com.coding.common.models.app.AppObjectRefDTO;

import java.util.Date;
import java.util.List;
import java.util.Map;

/** 一次发布。snapshot / objects 只在详情里返回；快照不含口令。 */
public record AppReleaseView(Integer releaseNo, String type, Integer sourceReleaseNo, String note, String status,
                             String createdBy, Date createdAt, boolean live,
                             Map<String, Object> snapshot, List<AppObjectRefDTO> objects) {
}
