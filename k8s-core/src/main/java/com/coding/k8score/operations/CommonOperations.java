package com.coding.k8score.operations;

import java.util.List;
import java.util.Map;

public interface CommonOperations<T> {

    String apiVersion();

    List<T> list(String namespace, Map<String, String> labels );

    T get(String namespace, String name);

    T create(T resource);

    T update(T resource);

    void delete(String namespace, String name);

}
