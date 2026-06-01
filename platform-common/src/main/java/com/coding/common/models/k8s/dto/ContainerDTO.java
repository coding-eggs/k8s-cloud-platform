package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

@Data
public class ContainerDTO {

    private String name;

    private String image;

    private List<EnvDTO> envs;

    private List<PortDTO> ports;

}
