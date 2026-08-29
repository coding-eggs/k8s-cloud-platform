package com.coding.k8sserver.configs;


import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.system.ResponseData;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
 * GlobalExceptionHandler 全局异常处理器，对特定异常进行统一处理：
 * MovieException 自定义异常
 * HttpMessageNotReadableException json转换异常
 * ConstraintViolationException get 参数校验异常
 * MethodArgumentNotValidException post参数校验异常
* @author myk
*/
@Slf4j
@Order(0)
@ControllerAdvice
public class GlobalExceptionHandler {

    public static final String ERROR_STRING = "{}：code:{} # msg:{}";

    @ResponseBody
    @ExceptionHandler(value = CloudPlatformException.class)
    public ResponseData<String> handleMovieException(CloudPlatformException movieException){
        log.error(ERROR_STRING,movieException.getClass().getName(),movieException.getCode(),movieException.getMsg());
        return new ResponseData<>(movieException.getCode(),movieException.getMsg(),null);
    }





//    @ExceptionHandler(NoResourceFoundException.class)
//    public ResponseEntity<String> handleNoResourceFound(NoResourceFoundException ex) {
//        return ResponseEntity.status(HttpStatus.NOT_FOUND)
//                .body("Resource not found: " + ex.getResourcePath());
//    }

    /**
     * 其他异常处理
     * @param exception 其他异常
     * @return 同意返回错误结果
     */
    @ResponseBody
    @ExceptionHandler(value = Exception.class)
    public ResponseData<String> handleOtherError(Exception exception){
        log.error("【异常】：",exception);
        return new ResponseData<>(EnumResponseType.ERROR.getCode(),null);
    }



    /**
    * 处理json 转换失败异常
    * @author myk
    */
    @ResponseBody
    @ExceptionHandler(value = HttpMessageNotReadableException.class)
    public ResponseData<String> handleInvalidFormatException(HttpMessageNotReadableException notReadableException){
        log.error("【json入参转换异常】：",notReadableException);
        log.error(ERROR_STRING,notReadableException.getClass().getName(), EnumResponseType.INVALID_FORMAT_FAIL.getCode(),notReadableException.getMessage());
        InvalidFormatException invalidFormatException = (InvalidFormatException) notReadableException.getCause();
        List<String> referenceList = invalidFormatException.getPath().stream().map(JacksonException.Reference::getPropertyName).collect(Collectors.toList());
        return new ResponseData<>(EnumResponseType.INVALID_FORMAT_FAIL.getCode(),
                EnumResponseType.INVALID_FORMAT_FAIL.getMsg() +
                        "【"+ String.join("," ,referenceList) +":"+  invalidFormatException.getValue() +"】" +
                        "无法转换为" + invalidFormatException.getTargetType().getName(), null);
    }


    /***
     * 参数异常 -- ConstraintViolationException()
     * 用于处理类似http://localhost:8080/user/getUser?age=30&name=yoyo请求中age和name的校验引发的异常
     */
    @ExceptionHandler(value = {ConstraintViolationException.class})
    @ResponseBody
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseData<String> urlParametersExceptionHandle(ConstraintViolationException e) {
        log.error("【请求参数异常】:", e);
        //收集所有错误信息
        List<String> errorMsg = e.getConstraintViolations()
                .stream().map(constraintViolation -> constraintViolation.getPropertyPath() + constraintViolation.getMessage()).collect(Collectors.toList());
        ResponseData<String> responseData = new ResponseData<>();
        responseData.setCode(EnumResponseType.BEAN_VALIDATION_EXCEPTION.getCode());
        responseData.setMsg(String.join(",",errorMsg));
        return responseData;
    }

    /***
     * 参数异常 --- MethodArgumentNotValidException和BindException
     * MethodArgumentNotValidException --- 用于处理请求参数为实体类时校验引发的异常 --- Content-Type为application/json
     * BindException --- 用于处理请求参数为实体类时校验引发的异常  --- Content-Type为application/x-www-form-urlencoded
     */
    @ExceptionHandler(value = {MethodArgumentNotValidException.class, BindException.class})
    @ResponseBody
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseData<String> bodyExceptionHandle(Exception e) {
        ResponseData<String> responseData = new ResponseData<>();
        responseData.setCode(EnumResponseType.METHOD_ARGUMENT_NOT_VALID_EXCEPTION.getCode());
        log.error("【请求参数异常】:", e);
        BindingResult bindingResult = null;
        if (e instanceof MethodArgumentNotValidException) {
            MethodArgumentNotValidException ex = (MethodArgumentNotValidException) e;
            bindingResult = ex.getBindingResult();
        } else {
            BindException ex = (BindException) e;
            ex.printStackTrace();
        }
        if (bindingResult != null) {
            //收集所有错误信息
            List<String> errorMsg = bindingResult.getFieldErrors().stream().map(fieldError -> fieldError.getDefaultMessage()+"【 " + fieldError.getField()+ " 】").collect(Collectors.toList());;

            responseData.setMsg(String.join(",",errorMsg));
        }
        return responseData;
    }
}
