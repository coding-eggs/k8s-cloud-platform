package com.coding.common.models.app;

import lombok.Data;

/** 一个对象的现读状态：READY / PENDING / MISSING / FAILED。 */
@Data
public class AppObjectStatusDTO {

    private String kind;

    private String name;

    private String state;

    private String message;
}
