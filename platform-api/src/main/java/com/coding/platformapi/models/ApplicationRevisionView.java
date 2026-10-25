package com.coding.platformapi.models;

import lombok.Data;

import java.util.Date;

/** 版本历史里的一行（不返回 manifest，避免列表页拖大包）。 */
@Data
public class ApplicationRevisionView {

    private Integer revision;

    private String status;

    private String message;

    private Date createdAt;
}
