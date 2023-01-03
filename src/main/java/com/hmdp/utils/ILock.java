package com.hmdp.utils;

public interface ILock {

    /**
     * 尝试获取锁
     *
     * @param timeOutSec 持有锁的超时时间，超时就自动释放
     * @return 是否获得锁
     */
    boolean tryLock(long timeOutSec);

    void unLock();
}
