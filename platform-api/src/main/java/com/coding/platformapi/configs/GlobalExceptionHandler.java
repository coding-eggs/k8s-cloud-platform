package com.coding.platformapi.configs;


import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.system.ResponseData;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常处理器（与 k8s-server 保持一致的响应结构）
 */
@Slf4j
@Order(0)
@ControllerAdvice
public class GlobalExceptionHandler {

    public static final String ERROR_STRING = "{}：code:{} # msg:{}";

    @ResponseBody
    @ExceptionHandler(value = CloudPlatformException.class)
    public ResponseData<String> handleCloudPlatformException(CloudPlatformException exception){
        log.error(ERROR_STRING,exception.getClass().getName(),exception.getCode(),exception.getMsg());
        return new ResponseData<>(exception.getCode(),exception.getMsg(),null);
    }

    /**
     * JWT 缺失/无效 → 未登录（前端据此跳转登录）
     */
    @ResponseBody
    @ExceptionHandler(value = AuthenticationException.class)
    public ResponseData<String> handleAuthenticationException(AuthenticationException exception){
        log.info("认证失败：{}", exception.getMessage());
        return new ResponseData<>(EnumResponseType.USER_UN_LOGIN.getCode(),null);
    }

    /**
     * 其他异常处理
     */
    @ResponseBody
    @ExceptionHandler(value = Exception.class)
    public ResponseData<String> handleOtherError(Exception exception){
        log.error("【异常】：",exception);
        return new ResponseData<>(EnumResponseType.ERROR.getCode(),null);
    }

    /**
    * 处理json 转换失败异常
    */
    @ResponseBody
    @ExceptionHandler(value = HttpMessageNotReadableException.class)
    public ResponseData<String> handleInvalidFormatException(HttpMessageNotReadableException notReadableException){
        log.error("【json入参转换异常】：",notReadableException);
        if (notReadableException.getCause() instanceof InvalidFormatException invalidFormatException) {
            List<String> referenceList = invalidFormatException.getPath().stream().map(JacksonException.Reference::getPropertyName).collect(Collectors.toList());
            return new ResponseData<>(EnumResponseType.INVALID_FORMAT_FAIL.getCode(),
                    EnumResponseType.INVALID_FORMAT_FAIL.getMsg() +
                            "【"+ String.join("," ,referenceList) +":"+  invalidFormatException.getValue() +"】" +
                            "无法转换为" + invalidFormatException.getTargetType().getName(), null);
        }
        return new ResponseData<>(EnumResponseType.INVALID_FORMAT_FAIL.getCode(),null);
    }

    /***
     * 参数异常 -- ConstraintViolationException()
     */
    @ExceptionHandler(value = {ConstraintViolationException.class})
    @ResponseBody
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseData<String> urlParametersExceptionHandle(ConstraintViolationException e) {
        log.error("【请求参数异常】:", e);
        List<String> errorMsg = e.getConstraintViolations()
                .stream().map(constraintViolation -> constraintViolation.getPropertyPath() + constraintViolation.getMessage()).collect(Collectors.toList());
        ResponseData<String> responseData = new ResponseData<>();
        responseData.setCode(EnumResponseType.BEAN_VALIDATION_EXCEPTION.getCode());
        responseData.setMsg(String.join(",",errorMsg));
        return responseData;
    }

    /***
     * 参数异常 --- MethodArgumentNotValidException和BindException
     */
    @ExceptionHandler(value = {MethodArgumentNotValidException.class, BindException.class})
    @ResponseBody
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseData<String> bodyExceptionHandle(Exception e) {
        ResponseData<String> responseData = new ResponseData<>();
        responseData.setCode(EnumResponseType.METHOD_ARGUMENT_NOT_VALID_EXCEPTION.getCode());
        log.error("【请求参数异常】:", e);
        BindingResult bindingResult = null;
        if (e instanceof MethodArgumentNotValidException ex) {
            bindingResult = ex.getBindingResult();
        } else if (e instanceof BindException ex) {
            bindingResult = ex.getBindingResult();
        }
        if (bindingResult != null) {
            List<String> errorMsg = bindingResult.getFieldErrors().stream()
                    .map(fieldError -> fieldError.getDefaultMessage()+"【 " + fieldError.getField()+ " 】")
                    .collect(Collectors.toList());
            responseData.setMsg(String.join(",",errorMsg));
        }
        return responseData;
    }
}
