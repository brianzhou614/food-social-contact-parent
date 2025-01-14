package com.fayayo.diners.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.StrUtil;
import com.fayayo.commons.constant.ApiConstant;
import com.fayayo.commons.constant.PointTypesConstant;
import com.fayayo.commons.exception.ParameterException;
import com.fayayo.commons.model.domain.ResultInfo;
import com.fayayo.commons.utils.AssertUtil;
import com.fayayo.commons.vo.SignInDinerInfo;
import io.swagger.annotations.Api;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;

/**
 * @author dalizu on 2021/2/28.
 * @version v1.0
 * @desc 签到业务逻辑
 */
@Service
public class SignService {

    @Value("${service.name.ms-oauth-server}")
    private String oauthServerName;

    @Value("${service.name.ms-points-server}")
    private String pointsServerName;

    @Resource
    private RedisTemplate redisTemplate;

    @Resource
    private RestTemplate restTemplate;

    /**
     * 获取当月的签到情况
     * */
    public Map<String,Boolean> getSignInfo(String accessToken, String dateStr){
        //获取用户登录信息
        SignInDinerInfo dinerInfo=loadSignInDinerInfo(accessToken);
        //获取月份
        Date date=getDate(dateStr);
        //构建key
        String signKey=buildSignKey(dinerInfo.getId(),date);

        //构建一个自动排序的map
        Map<String,Boolean>singInfo=new TreeMap<>();
        //获取某月的总天数(考虑闰年)
        int dayOfMonth=DateUtil.lengthOfMonth(DateUtil.month(date)+1,
                DateUtil.isLeapYear(DateUtil.dayOfYear(date)));

        //bitfield user:sign:5:202011 u30 0
        BitFieldSubCommands bitFieldSubCommands=BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                .valueAt(0);
        List<Long>list=redisTemplate.opsForValue().bitField(signKey,bitFieldSubCommands);
        if(list==null||list.isEmpty()){
            return singInfo;
        }
        long v=list.get(0) ==null?0:list.get(0);
         //从低到高进行遍历,为0表示未签到,为1表示已经签到
        for (int i=dayOfMonth;i>0;i--){
            //yyyy-MM-dd true
            LocalDateTime localDateTime=LocalDateTimeUtil.of(date).withDayOfMonth(i);
            boolean flag=v>> 1 <<1 !=v;
            singInfo.put(DateUtil.format(localDateTime,"yyyy-MM-dd"),false);
            v=v >> 1;
        }
        return singInfo;
    }


    /**
     * 获取用户签到次数
     * */
    public long getSignCount(String accessToken,String dateStr){

        //获取用户登录信息
        SignInDinerInfo dinerInfo=loadSignInDinerInfo(accessToken);
        //获取月份
        Date date=getDate(dateStr);
        //构建key
        String key=buildSignKey(dinerInfo.getId(),date);

        return (long)redisTemplate.execute(
                (RedisCallback<Long>)con->con.bitCount(key.getBytes())
        );
    }



    /**
     * 用户签到
     * */
    public int doSign(String accessToken,String dateStr){
        //获取用户登录信息
        SignInDinerInfo dinerInfo=loadSignInDinerInfo(accessToken);
        //获取日期
        Date date=getDate(dateStr);
        //获取日期对应的天,多少号 从0开始
        int offset=DateUtil.dayOfMonth(date)-1;
        //查看是否已经签到
        String signKey=buildSignKey(dinerInfo.getId(),date);
        boolean isSigned=redisTemplate.opsForValue().getBit(signKey,offset);
        AssertUtil.isTrue(isSigned,"当前日期以完成签到,无需再签");
        //签到
        redisTemplate.opsForValue().setBit(signKey,offset,true);
        //统计连续签到的次数
        int count=getContinuousSignCount(dinerInfo.getId(),date);

        //添加签到积分 TODO 可以异步做
        int points= addPoints(count,dinerInfo.getId());
        return points;
    }

    /**
     * 统计连续签到次数
     * */
    private int getContinuousSignCount(Integer dinerId, Date date) {
        //获取日期对应的天数 比如11月有 31天
        int dayOfMonth=DateUtil.dayOfMonth(date);
        //bitfield user:sing:5:202011  u31 0
        String signKey=buildSignKey(dinerId,date);
        //从0开始到31获取签到的天总数
        BitFieldSubCommands bitFieldSubCommands=BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                .valueAt(0);
        List<Long>list=redisTemplate.opsForValue().bitField(signKey,bitFieldSubCommands);
        if(list==null||list.isEmpty()){
            return 0;
        }
        int signCount=0;
        long v=list.get(0) ==null?0:list.get(0);

        //i表示位移操作的次数
        for (int i=dayOfMonth;i>0;i--){
            //右移然后再左移,如果等于自己说明最低位是0,表示未签到
            if(v >> 1 << 1 ==v){
                //低位是 0  且非当天说明连续签到中断了
                if(i !=dayOfMonth) break;
            }else {
                signCount++;
            }
            //右移一位并重新赋值,相当于把最低位丢弃一位
            v=v>>1;
        }

        return signCount;
    }

    /**
     * key--user:sign:5:yyyyMM
     * */
    private String buildSignKey(Integer dinerId, Date date) {
       return String.format("user:sign:%d:%s",dinerId,
               DateUtil.format(date,"yyyyMM"));
    }

    private Date getDate(String dateStr) {
        if(StrUtil.isBlank(dateStr)){
            return new Date();
        }
        try {
            return DateUtil.parseDate(dateStr);
        }catch (Exception e){
            throw new ParameterException("请传入yyyy-MM-dd的日期格式");
        }
    }


    //获取登录用户信息
    private SignInDinerInfo loadSignInDinerInfo(String accessToken) {
        AssertUtil.mustLogin(accessToken);
        String url=oauthServerName+"user/me?access_token={accessToken}";
        ResultInfo resultInfo=restTemplate.getForObject(url,ResultInfo.class,accessToken);
        if(resultInfo.getCode()!= ApiConstant.SUCCESS_CODE){
            throw new ParameterException(resultInfo.getMessage());
        }
        SignInDinerInfo dinerInfo= BeanUtil.fillBeanWithMap((LinkedHashMap)resultInfo.getData(),
                new SignInDinerInfo(),false);

        if(dinerInfo==null){
            throw new ParameterException(ApiConstant.NO_LOGIN_CODE,ApiConstant.NO_LOGIN_MESSAGE);
        }

        return dinerInfo;
    }


    /**
     * 添加用户积分
     *
     * @param count         连续签到次数
     * @param signInDinerId 登录用户id
     * @return 获取的积分
     */
    private int addPoints(int count, Integer signInDinerId) {
        // 签到1天送10积分，连续签到2天送20积分，3天送30积分，4天以上均送50积分
        int points = 10;
        if (count == 2) {
            points = 20;
        } else if (count == 3) {
            points = 30;
        } else if (count >= 4) {
            points = 50;
        }
        // 调用积分接口添加积分
        // 构建请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        // 构建请求体（请求参数）
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("dinerId", signInDinerId);
        body.add("points", points);
        body.add("types", PointTypesConstant.sign.getType());
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
        // 发送请求
        ResponseEntity<ResultInfo> result = restTemplate.postForEntity(pointsServerName,
                entity, ResultInfo.class);
        AssertUtil.isTrue(result.getStatusCode() != HttpStatus.OK, "登录失败！");
        ResultInfo resultInfo = result.getBody();
        if (resultInfo.getCode() != ApiConstant.SUCCESS_CODE) {
            // 失败了, 事物要进行回滚
            throw new ParameterException(resultInfo.getCode(), resultInfo.getMessage());
        }
        return points;
    }


}
