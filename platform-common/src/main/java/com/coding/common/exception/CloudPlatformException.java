package com.coding.common.exception;

import lombok.Getter;

@Getter
public class CloudPlatformException extends RuntimeException {

    private final Integer code;

    private final String msg;


    public CloudPlatformException(Integer code, String msg, Throwable throwable){
        super(msg,throwable);
        this.code = code;
        this.msg = msg;
    }

    public CloudPlatformException(EnumResponseType responseType){
        super(responseType.getMsg());
        this.code = responseType.getCode();
        this.msg = responseType.getMsg();
    }
    //
    public CloudPlatformException(EnumResponseType responseType, String msg){
        super(msg);
        this.code = responseType.getCode();
        this.msg = msg;
    }

    /**
     * 透传下游服务（如 k8s-server）返回的错误码与消息
     */
    public CloudPlatformException(Integer code, String msg){
        super(msg);
        this.code = code;
        this.msg = msg;
    }

}
