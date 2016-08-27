package com.virjar.scheduler;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;

import javax.annotation.Resource;

import org.apache.http.HttpHost;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import com.google.common.collect.Lists;
import com.virjar.model.ProxyModel;
import com.virjar.service.ProxyService;
import com.virjar.utils.ProxyUtil;
import com.virjar.utils.ScoreUtil;
import com.virjar.utils.SysConfig;

@Component
public class ConnectionValidater implements Runnable, InitializingBean {

    @Resource
    private ProxyService proxyService;

    private ExecutorService pool = Executors.newFixedThreadPool(SysConfig.getInstance().getConnectionCheckThread());

    private Logger logger = Logger.getLogger(ConnectionValidater.class);

    @Override
    public void afterPropertiesSet() throws Exception {
        new Thread(this).start();
    }

    public void run() {
        long totalWaitTime = 10 * 60 * 1000;
        logger.info("Component start");
        while (true) {
            try {
                List<ProxyModel> needupdate = proxyService.find4connectionupdate();
                if (needupdate.size() == 0) {
                    logger.info("no proxy need to update");
                    return;
                }
                List<Future<Object>> futures = Lists.newArrayList();
                for (ProxyModel proxy : needupdate) {
                    futures.add(pool.submit(new ProxyTester(proxy)));
                }
                long start = System.currentTimeMillis();
                for (Future<Object> future : futures) {
                    try {
                        // 等待十分钟
                        future.get(totalWaitTime + start - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } catch (Exception e) {
                logger.error("error", e);
            }
        }
    }

    private class ProxyTester implements Callable<Object> {
        private ProxyModel proxy;

        public ProxyTester(ProxyModel proxy) {
            super();
            this.proxy = proxy;
        }

        @Override
        public Object call() throws Exception {
            Long connectionScore = proxy.getConnectionScore();
            long slot = ScoreUtil.calAvailableSlot(connectionScore);
            slot = slot == 0 ? 1 : slot;
            try {
                if (ProxyUtil
                        .validateProxyConnect(new HttpHost(InetAddress.getByName(proxy.getIp()), proxy.getPort()))) {
                    if (proxy.getConnectionScore() < 0) {
                        proxy.setConnectionScore(
                                proxy.getConnectionScore() + slot * SysConfig.getInstance().getSlotFactory());
                    } else {
                        proxy.setConnectionScore(proxy.getConnectionScore() + 1);
                    }
                } else {
                    if (proxy.getConnectionScore() > 0) {
                        proxy.setConnectionScore(
                                proxy.getConnectionScore() - slot * SysConfig.getInstance().getSlotFactory());
                    } else {
                        proxy.setConnectionScore(proxy.getConnectionScore() + 1);
                    }
                }
                ProxyModel updateProxy = new ProxyModel();
                updateProxy.setConnectionScore(proxy.getConnectionScore());
                updateProxy.setId(proxy.getId());
                updateProxy.setConnectionScoreDate(new Date());
                proxyService.updateByPrimaryKeySelective(updateProxy);
            } catch (UnknownHostException e) {
                proxyService.deleteByPrimaryKey(proxy.getId());
            }
            return this;
        }
    }
}