package com.coding.platformapi.controllers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class TestController {

    public static void main(String[] args) {

        List<Integer> ar = Stream.of(10, 5,12).sorted().toList();

        List<Integer> br = Stream.of(4, 11, 20, 1).sorted().toList();

        List<Integer> list = new ArrayList<>();

        int i = 0;
        int j = 0;
        while (i < ar.size() || j < br.size()) {

            if (j > br.size() - 1) {
                list.addAll(ar.subList(i, ar.size()));
                break;
            }
            if (i > ar.size() - 1) {
                list.addAll(br.subList(j, br.size()));
                break;
            }

            if (ar.get(i) <= br.get(j)) {
                list.add(ar.get(i));
                i++;
                continue;
            }
            if (ar.get(i) > br.get(j)) {
                list.add(br.get(j));
                j ++;
            }
        }

        System.out.printf(list.toString());

    }

}
