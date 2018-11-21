package com.tm.seckill.service;

import com.tm.seckill.bean.Exposer;
import com.tm.seckill.bean.SeckillExecution;
import com.tm.seckill.entity.Seckill;
import com.tm.seckill.entity.SuccessKilled;
import com.tm.seckill.enums.SeckillStateEnum;
import com.tm.seckill.exception.RepeatKillException;
import com.tm.seckill.exception.SeckillCloseException;
import com.tm.seckill.exception.SeckillException;
import com.tm.seckill.mapper.*;
import org.apache.commons.collections.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SeckillService {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    // 注入Service依赖
    @Autowired
    private SeckillMapper seckillMapper;

    @Autowired
    private SuccessKilledMapper successKilledMapper;

    @Autowired
    private RedisMapper redisMapper;

    // md5盐值字符串，用于混淆MD5
    private final String slat = "skdfjksjdf7787%^%^%^FSKJFK*(&&%^%&^8DF8^%^^*7hFJDHFJ";

    public List<Seckill> getSeckillList() {
        return seckillMapper.queryAll(0, 4);
    }

    public Seckill getById(long seckillId) {
        return seckillMapper.queryById(seckillId);
    }

    private String getMD5(long seckillId) {
        String base = seckillId + "/" + slat;
        String md5 = DigestUtils.md5DigestAsHex(base.getBytes());
        return md5;
    }

    public Exposer exportSeckillUrl(long seckillId) {
        // 优化点：缓存优化：超时的基础上维护一致性
        // 1.访问redis
        Seckill seckill = redisMapper.getSeckill(seckillId);
        if (seckill == null) {
            // 2.访问数据库
            seckill = seckillMapper.queryById(seckillId);
            if (seckill == null) {
                return new Exposer(false, seckillId);
            } else {
                // 3.访问redis
                redisMapper.putSeckill(seckill);
            }
        }
        if (seckill == null) {
            return new Exposer(false, seckillId);
        }
        Date startTime = seckill.getStartTime();
        Date endTime = seckill.getEndTime();
        // 系统当前时间
        Date nowTime = new Date();
        if (nowTime.getTime() < startTime.getTime() || nowTime.getTime() > endTime.getTime()) {
            return new Exposer(false, seckillId, nowTime.getTime(), startTime.getTime(), endTime.getTime());
        }
        // 转化特定字符串的过程，不可逆
        String md5 = getMD5(seckillId);
        return new Exposer(true, md5, seckillId);
    }

    @Transactional
    public SeckillExecution executeSeckill(long seckillId, long userPhone, String md5) throws SeckillException, RepeatKillException, SeckillCloseException {
        if (md5 == null || !md5.equals(getMD5(seckillId))) {
            throw new SeckillException("seckill data rewrite");
        }
        // 执行秒杀逻辑：减库存 + 记录购买行为
        Date now = new Date();
        try {
            // 记录购买行为
            int insertCount = successKilledMapper.insertSuccessKilled(seckillId, userPhone);
            // 唯一：seckillId,userPhone
            if (insertCount <= 0) {
                // 重复秒杀
                throw new RepeatKillException("seckill repeated");
            } else {
                // 减库存，热点商品竞争
                int updateCount = seckillMapper.reduceNumber(seckillId, now);
                if (updateCount <= 0) {
                    // 没有更新到记录 rollback
                    throw new SeckillCloseException("seckill is closed");
                } else {
                    // 秒杀成功 commit
                    SuccessKilled successKilled = successKilledMapper.queryByIdWithSeckill(seckillId, userPhone);
                    return new SeckillExecution(seckillId, SeckillStateEnum.SUCCESS, successKilled);
                }
            }
        } catch (SeckillCloseException e1) {
            throw e1;
        } catch (RepeatKillException e2) {
            throw e2;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            // 所有编译期异常转换为运行期异常
            throw new SeckillException("seckill inner error:" + e.getMessage());
        }
    }

    public SeckillExecution executeSeckillProcedure(long seckillId, long userPhone, String md5) {
        if (md5 == null || !md5.equals(getMD5(seckillId))) {
            return new SeckillExecution(seckillId, SeckillStateEnum.DATA_REWRITE);
        }
        Date killTime = new Date();
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("seckillId", seckillId);
        map.put("phone", userPhone);
        map.put("killTime", killTime);
        map.put("result", null);
        // 执行存储过程，result被赋值
        try {
            seckillMapper.killByProcedure(map);
            // 获取result
            int result = MapUtils.getInteger(map, "result", -2);
            if (result == 1) {
                SuccessKilled sk = successKilledMapper.queryByIdWithSeckill(seckillId, userPhone);
                return new SeckillExecution(seckillId, SeckillStateEnum.SUCCESS, sk);
            } else {
                return new SeckillExecution(seckillId, SeckillStateEnum.stateOf(result));
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return new SeckillExecution(seckillId, SeckillStateEnum.INNER_ERROR);
        }
    }
}
