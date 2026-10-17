package com.coding.data.mapper.k8s;

import com.coding.data.models.k8s.K8sCluster;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8sClusterMapper {
    int deleteByPrimaryKey(String clusterId);

    int insert(K8sCluster record);

    int insertSelective(K8sCluster record);

    K8sCluster selectByPrimaryKey(@Param("clusterId") String clusterId);

    int updateByPrimaryKeySelective(K8sCluster record);

    int updateByPrimaryKey(K8sCluster record);

    List<K8sCluster> listAll();

    /** 某画像的集群数量（创建/编辑时保证 MANAGEMENT 全局唯一） */
    int countByRole(@Param("clusterRole") String clusterRole);
}