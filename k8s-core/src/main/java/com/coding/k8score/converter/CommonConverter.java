package com.coding.k8score.converter;

public interface CommonConverter<R, I> {

    R convert(I input);

    I revert(R input);
}
