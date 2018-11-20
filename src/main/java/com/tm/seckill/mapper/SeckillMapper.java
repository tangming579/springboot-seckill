package com.tm.seckill.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.Date;

public interface SeckillMapper {

    /**
     * 减库存
     * @param seckillId
     * @param killTime
     * @return 如果影响行数等于>1，表示更新的记录行数
     */
    int reduceNumber(@Param("seckillId")long seckillId, @Param("killTime") Date killTime);

}
