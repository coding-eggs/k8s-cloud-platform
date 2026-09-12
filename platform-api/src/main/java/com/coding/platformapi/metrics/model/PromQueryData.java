package com.coding.platformapi.metrics.model;

import lombok.Data;

import java.util.List;

/** envelope.data：{@code { resultType, result[] }}。resultType = "vector"（即时）/ "matrix"（区间）。 */
@Data
public class PromQueryData<R> {

    private String resultType;

    private List<R> result;

}
