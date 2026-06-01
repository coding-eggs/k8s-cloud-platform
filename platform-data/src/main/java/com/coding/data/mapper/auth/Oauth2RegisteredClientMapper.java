package com.coding.data.mapper.auth;

import com.coding.data.models.auth.Oauth2RegisteredClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface Oauth2RegisteredClientMapper {
    int deleteByPrimaryKey(String id);

    int insert(Oauth2RegisteredClient record);

    int insertSelective(Oauth2RegisteredClient record);

    Oauth2RegisteredClient selectByPrimaryKey(String id);

    List<Oauth2RegisteredClient> selectList();

    int updateByPrimaryKeySelective(Oauth2RegisteredClient record);

    int updateByPrimaryKey(Oauth2RegisteredClient record);
}