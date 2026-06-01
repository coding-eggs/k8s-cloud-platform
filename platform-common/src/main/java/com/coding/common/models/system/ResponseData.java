package com.coding.common.models.system;
import com.coding.common.exception.EnumResponseType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
public class ResponseData<T> {

    /**
     * 响应代码
     */
    @Schema(name = "状态码",example = "200")
    private Integer code;

    /**
     * 响应消息
     */
    @Setter
    @Schema(name = "响应提示",example = "成功")
    private String msg;

    /**
     * 响应结果
     */
    @Setter
    @Schema(name = "响应数据")
    private T data;

    public ResponseData(){

    }


    public ResponseData(Integer code , String msg, T data) {
        this.code = code;
        this.data = data;
        this.msg = msg;
    }

    public ResponseData(Integer code, T data) {
        this.code = code;
        this.data = data;
        this.msg = EnumResponseType.getMsgByCode(code);
    }

    public ResponseData(EnumResponseType enumResponseType, T data) {
        this.code = enumResponseType.getCode();
        this.data = data;
        this.msg = EnumResponseType.getMsgByCode(code);
    }

    public ResponseData(T data) {
        this.code = EnumResponseType.SUCCESS.getCode();
        this.msg = EnumResponseType.SUCCESS.getMsg();
        this.data = data;
    }

    public void setCode(Integer code) {
        this.code = code;
        this.msg = EnumResponseType.getMsgByCode(code);
    }

}
