package com.coding.platformapi.models;

import com.coding.common.models.app.AppSpecDTO;
import lombok.Data;

import java.util.Date;

/** 应用的对外视图。 */
@Data
public class ApplicationView {

    private String id;

    private String clusterId;

    private String namespace;

    private String name;

    private String slug;

    private String displayName;

    private String description;

    private String status;

    private Integer currentRevision;

    private AppSpecDTO spec;

    private Date createdAt;

    private Date updatedAt;
}
