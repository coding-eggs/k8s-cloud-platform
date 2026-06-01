package com.coding.data.mapper.auth;

import com.coding.data.models.auth.Oauth2Jwk;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface Oauth2JwkMapper {
    int deleteByPrimaryKey(Long id);

    int insert(Oauth2Jwk record);

    int insertSelective(Oauth2Jwk record);

    Oauth2Jwk selectByPrimaryKey(Long id);

    int updateByPrimaryKeySelective(Oauth2Jwk record);

    int updateByPrimaryKey(Oauth2Jwk record);

    List<Oauth2Jwk> selectAll();

}