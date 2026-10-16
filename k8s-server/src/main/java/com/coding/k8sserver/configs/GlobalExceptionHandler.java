package com.coding.k8sserver.configs;


import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.system.ResponseData;
import io.fabric8.kubernetes.client.KubernetesClientException;
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

    /**
     * 集群侧 403：翻译成一句能照着做的话。
     *
     * <p>为什么值得单独一个 handler：{@link KubernetesClientException} 原先落进 {@link #handleOtherError}，
     * 响应里只剩 code=5000 /「服务端异常」，而 apiserver 原文 ——
     * {@code servicemonitors.monitoring.coreos.com is forbidden: User "...:tn-default" cannot list resource
     * "servicemonitors" in API group "monitoring.coreos.com"} —— 是<b>唯一</b>能指出缺哪条规则的信息，被整个吞掉了。
     * 租户 2026-10-17 报的「CRD 页面全打不开」就卡在这里：界面说不出缺什么，运维也就无从下手。
     *
     * <p>修在这一层（而不是前端逐页写 403 文案）是因为前端本来就在渲染 {@code body.msg}
     * （{@code api/http.ts} 的响应拦截器），所有页面一次覆盖。
     *
     * <p><b>只翻译 403</b>，其余码原样交回 {@link #handleOtherError} —— 404/409/422 不是权限问题，
     * 一律套「检查 RBAC 模板」会把排障引到错的方向。
     */
    @ResponseBody
    @ExceptionHandler(value = KubernetesClientException.class)
    public ResponseData<String> handleKubernetesClientException(KubernetesClientException e) {
        if (e.getCode() != 403) {
            return handleOtherError(e);
        }
        //集群原文要进日志（它是排障依据），也要进 msg（用户/运维看得到）
        log.error(ERROR_STRING, e.getClass().getName(), EnumResponseType.K8S_RBAC_FORBIDDEN.getCode(), e.getMessage());
        return new ResponseData<>(EnumResponseType.K8S_RBAC_FORBIDDEN.getCode(),
                EnumResponseType.K8S_RBAC_FORBIDDEN.getMsg() + e.getMessage(), null);
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
