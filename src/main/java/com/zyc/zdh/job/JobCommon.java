package com.zyc.zdh.job;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hubspot.jinjava.Jinjava;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;
import com.zyc.zdh.dao.*;
import com.zyc.zdh.entity.*;
import com.zyc.zdh.quartz.QuartzManager2;
import com.zyc.zdh.service.EtlTaskService;
import com.zyc.zdh.service.ZdhLogsService;
import com.zyc.zdh.service.impl.DataSourcesServiceImpl;
import com.zyc.zdh.util.*;
import org.apache.shiro.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.LinkedBlockingDeque;

public class JobCommon {

    public static Logger logger = LoggerFactory.getLogger(JobCommon.class);

    public static String myid = "";

    public static String web_application_id="";

    public static LinkedBlockingDeque<ZdhLogs> linkedBlockingDeque = new LinkedBlockingDeque<ZdhLogs>();

    public static ConcurrentHashMap<String, Thread> chm = new ConcurrentHashMap<String, Thread>();

    public static DelayQueue<RetryJobInfo> retryQueue = new DelayQueue<>();


    public static void logThread(ZdhLogsService zdhLogsService) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        ZdhLogs log = JobCommon.linkedBlockingDeque.take();
                        if (log != null) {
                            zdhLogsService.insert(log);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

            }
        }).start();
    }

    public static void retryThread() {
        new Thread(new Runnable() {

            @Override
            public void run() {
                while (true) {
                    try {
                        RetryJobInfo retryJobInfo = retryQueue.take();
                        QuartzJobMapper quartzJobMapper = (QuartzJobMapper) SpringContext.getBean("quartzJobMapper");
                        QuartzJobInfo qj = quartzJobMapper.selectByPrimaryKey(retryJobInfo.getQuartzJobInfo().getJob_id());
                        if (qj.getLast_status().equalsIgnoreCase("retry")) {
                            logger.info("开始执行重试任务,job_id:" + qj.getJob_id() + ",job_context:" + qj.getJob_context());
                            insertLog(qj, "INFO", "开始执行重试任务,job_id:" + qj.getJob_id() + ",job_context:" + qj.getJob_context());
                            chooseJobBean(qj, true);
                        } else {
                            insertLog(qj, "INFO", "由于其他原因导致任务状态变更,无法进行重试,job_id:" + qj.getJob_id() + ",job_context:" + qj.getJob_context());
                            logger.info("由于其他原因导致任务状态变更,无法进行重试,job_id:" + qj.getJob_id() + ",job_context:" + qj.getJob_context());
                        }

                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }

    /**
     * 超过次数限制会主动杀掉调度,并设置状态为error
     * @param jobType        SHELL,JDBC,FTP,CLASS
     * @param quartzManager2
     * @param quartzJobInfo
     *  return true 表示超过次数限制
     */
    public static boolean isCount(String jobType, QuartzManager2 quartzManager2, QuartzJobInfo quartzJobInfo) {
        QuartzJobMapper quartzJobMapper = (QuartzJobMapper) SpringContext.getBean("quartzJobMapper");
        TaskLogsMapper taskLogsMapper = (TaskLogsMapper) SpringContext.getBean("taskLogsMapper");
        //判断次数,上次任务完成重置次数
        if (quartzJobInfo.getLast_status() == null || quartzJobInfo.getLast_status().equals("finish")) {
            quartzJobInfo.setCount(0);
        }
        quartzJobInfo.setCount(quartzJobInfo.getCount() + 1);

        //设置执行的进度
        TaskLogs taskLogs=taskLogsMapper.selectByPrimaryKey(quartzJobInfo.getTask_log_id());
        taskLogs.setProcess("8");

        if (quartzJobInfo.getPlan_count().trim().equals("-1")) {
            logger.info("[" + jobType + "] JOB ,当前任务未设置执行次数限制");
            insertLog(quartzJobInfo, "info", "[" + jobType + "] JOB ,当前任务未设置执行次数限制");
        }

        if (!quartzJobInfo.getPlan_count().trim().equals("") && !quartzJobInfo.getPlan_count().trim().equals("-1")) {
            //任务有次数限制,重试多次后仍失败会删除任务
            System.out.println(quartzJobInfo.getCount() + "================" + quartzJobInfo.getPlan_count().trim());
            if (quartzJobInfo.getCount() > Long.parseLong(quartzJobInfo.getPlan_count().trim())) {
                logger.info("[" + jobType + "] JOB 检任务次测到重试数超过限制,删除任务并直接返回结束");
                insertLog(quartzJobInfo, "info", "[" + jobType + "] JOB 检任务次测到重试数超过限制,删除任务并直接返回结束");
                quartzManager2.deleteTask(quartzJobInfo, "finish");
                quartzJobMapper.updateLastStatus(quartzJobInfo.getJob_id(), "error");

                insertLog(quartzJobInfo, "info", "[" + jobType + "] JOB ,结束调度任务");
                taskLogs.setStatus(InstanceStatus.ERROR.getValue());
                updateTaskLog(taskLogs,taskLogsMapper);
                return true;
            }
        }

        updateTaskLog(taskLogs,taskLogsMapper);
        return false;
    }

    public static void debugInfo(Object obj) {
        Field[] fields = obj.getClass().getDeclaredFields();
        for (int i = 0, len = fields.length; i < len; i++) {
            // 对于每个属性，获取属性名
            String varName = fields[i].getName();
            try {
                // 获取原来的访问控制权限
                boolean accessFlag = fields[i].isAccessible();
                // 修改访问控制权限
                fields[i].setAccessible(true);
                // 获取在对象f中属性fields[i]对应的对象中的变量
                Object o;
                try {
                    o = fields[i].get(obj);
                    logger.info("传入的对象中包含一个如下的变量：" + varName + " = " + o);
                } catch (IllegalAccessException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                // 恢复访问控制权限
                fields[i].setAccessible(accessFlag);
            } catch (IllegalArgumentException ex) {
                ex.printStackTrace();
            }
        }
    }


    public static ZdhInfo create_zhdInfo(QuartzJobInfo quartzJobInfo, QuartzJobMapper quartzJobMapper,
                                         EtlTaskService etlTaskService, DataSourcesServiceImpl dataSourcesServiceImpl, ZdhNginxMapper zdhNginxMapper, EtlMoreTaskMapper etlMoreTaskMapper) throws Exception {

        JSONObject json = new JSONObject();
        String date = DateUtil.formatTime(quartzJobInfo.getLast_time());
        json.put("ETL_DATE", date);
        logger.info(" JOB ,单源,处理当前日期,传递参数ETL_DATE 为" + date);
        quartzJobInfo.setParams(json.toJSONString());

        String etl_task_id = quartzJobInfo.getEtl_task_id();
        //获取etl 任务信息
        EtlTaskInfo etlTaskInfo = etlTaskService.selectById(etl_task_id);
        if (etlTaskInfo == null) {
            logger.info("无法找到对应的ETL任务,任务id:" + etl_task_id);
            throw new Exception("无法找到对应的ETL任务,任务id:" + etl_task_id);
        }

        Map<String, Object> map = (Map<String, Object>) JSON.parseObject(quartzJobInfo.getParams());
        //此处做参数匹配转换
        if (map != null) {
            logger.info("单源,自定义参数不为空,开始替换:" + quartzJobInfo.getParams());
            //System.out.println("自定义参数不为空,开始替换:" + dti.getParams());
            DynamicParams(map, quartzJobInfo, etlTaskInfo, null, null, null);
        }

        //获取数据源信息
        String data_sources_choose_input = etlTaskInfo.getData_sources_choose_input();
        String data_sources_choose_output = etlTaskInfo.getData_sources_choose_output();
        DataSourcesInfo dataSourcesInfoInput = dataSourcesServiceImpl.selectById(data_sources_choose_input);
        DataSourcesInfo dataSourcesInfoOutput = null;
        if (!data_sources_choose_input.equals(data_sources_choose_output)) {
            dataSourcesInfoOutput = dataSourcesServiceImpl.selectById(data_sources_choose_output);
        } else {
            dataSourcesInfoOutput = dataSourcesInfoInput;
        }

        if (dataSourcesInfoInput.getData_source_type().equals("外部上传")) {
            //获取文件服务器信息 配置到数据源选项
            ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(dataSourcesInfoInput.getOwner());
            if (zdhNginx != null && !zdhNginx.getHost().equals("")) {
                dataSourcesInfoInput.setUrl(zdhNginx.getHost() + ":" + zdhNginx.getPort());
                dataSourcesInfoInput.setUsername(zdhNginx.getUsername());
                dataSourcesInfoInput.setPassword(zdhNginx.getPassword());
            }
        }

        if (dataSourcesInfoOutput.getData_source_type().equals("外部下载")) {
            //获取文件服务器信息 配置到数据源选项
            ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(dataSourcesInfoOutput.getOwner());
            if (zdhNginx != null && !zdhNginx.getHost().equals("")) {
                dataSourcesInfoOutput.setUrl(zdhNginx.getHost() + ":" + zdhNginx.getPort());
                dataSourcesInfoOutput.setUsername(zdhNginx.getUsername());
                dataSourcesInfoOutput.setPassword(zdhNginx.getPassword());
                if (etlTaskInfo.getData_sources_params_output() != null && !etlTaskInfo.getData_sources_params_output().trim().equals("")) {
                    JSONObject jsonObject = JSON.parseObject(etlTaskInfo.getData_sources_params_output());
                    jsonObject.put("root_path", zdhNginx.getNginx_dir() + "/" + getUser().getId());
                    etlTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                } else {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("root_path", zdhNginx.getNginx_dir() + "/" + getUser().getId());
                    etlTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                }
            } else {
                if (etlTaskInfo.getData_sources_params_output() != null && !etlTaskInfo.getData_sources_params_output().trim().equals("")) {
                    JSONObject jsonObject = JSON.parseObject(etlTaskInfo.getData_sources_params_output());
                    jsonObject.put("root_path", zdhNginx.getTmp_dir() + "/" + getUser().getId());
                    etlTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                } else {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("root_path", zdhNginx.getTmp_dir() + "/" + getUser().getId());
                    etlTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                }
            }
        }

        ZdhInfo zdhInfo = new ZdhInfo();
        zdhInfo.setZdhInfo(dataSourcesInfoInput, etlTaskInfo, dataSourcesInfoOutput, quartzJobInfo);

        return zdhInfo;

    }

    public static ZdhMoreInfo create_more_task_zdhInfo(QuartzJobInfo quartzJobInfo, QuartzJobMapper quartzJobMapper,
                                                       EtlTaskService etlTaskService, DataSourcesServiceImpl dataSourcesServiceImpl, ZdhNginxMapper zdhNginxMapper, EtlMoreTaskMapper etlMoreTaskMapper) {
        try {
            JSONObject json = new JSONObject();
            String date = DateUtil.formatTime(quartzJobInfo.getLast_time());
            json.put("ETL_DATE", date);
            logger.info(" JOB ,多源,处理当前日期,传递参数ETL_DATE 为" + date);
            quartzJobInfo.setParams(json.toJSONString());

            String etl_task_id = quartzJobInfo.getEtl_task_id();

            //获取多源任务id
            EtlMoreTaskInfo etlMoreTaskInfo = etlMoreTaskMapper.selectByPrimaryKey(etl_task_id);

            //解析多源任务中的单任务
            String[] etl_ids = etlMoreTaskInfo.getEtl_ids().split(",");
            //获取etl 任务信息
            List<EtlTaskInfo> etlTaskInfos = etlTaskService.selectByIds(etl_ids);

            ZdhMoreInfo zdhMoreInfo = new ZdhMoreInfo();
            zdhMoreInfo.setEtlMoreTaskInfo(etlMoreTaskInfo);
            //获取最终输出数据源
            String data_sources_choose_output = etlMoreTaskInfo.getData_sources_choose_output();
            DataSourcesInfo dataSourcesInfoOutput = dataSourcesServiceImpl.selectById(data_sources_choose_output);

            if (dataSourcesInfoOutput.getData_source_type().equals("外部下载")) {
                //获取文件服务器信息 配置到数据源选项
                ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(dataSourcesInfoOutput.getOwner());
                if (zdhNginx != null && !zdhNginx.getHost().equals("")) {
                    dataSourcesInfoOutput.setUrl(zdhNginx.getHost() + ":" + zdhNginx.getPort());
                    dataSourcesInfoOutput.setUsername(zdhNginx.getUsername());
                    dataSourcesInfoOutput.setPassword(zdhNginx.getPassword());
                    if (etlMoreTaskInfo.getData_sources_params_output() != null && !etlMoreTaskInfo.getData_sources_params_output().trim().equals("")) {
                        JSONObject jsonObject = JSON.parseObject(etlMoreTaskInfo.getData_sources_params_output());
                        jsonObject.put("root_path", zdhNginx.getNginx_dir() + "/" + getUser().getId());
                        etlMoreTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    } else {
                        JSONObject jsonObject = new JSONObject();
                        jsonObject.put("root_path", zdhNginx.getNginx_dir() + "/" + getUser().getId());
                        etlMoreTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    }
                } else {
                    if (etlMoreTaskInfo.getData_sources_params_output() != null && !etlMoreTaskInfo.getData_sources_params_output().trim().equals("")) {
                        JSONObject jsonObject = JSON.parseObject(etlMoreTaskInfo.getData_sources_params_output());
                        jsonObject.put("root_path", zdhNginx.getTmp_dir() + "/" + getUser().getId());
                        etlMoreTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    } else {
                        JSONObject jsonObject = new JSONObject();
                        jsonObject.put("root_path", zdhNginx.getTmp_dir() + "/" + getUser().getId());
                        etlMoreTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    }
                }
            }


            Map<String, Object> map = (Map<String, Object>) JSON.parseObject(quartzJobInfo.getParams());
            //此处做参数匹配转换
            for (EtlTaskInfo etlTaskInfo : etlTaskInfos) {
                if (map != null) {
                    logger.info("多源,自定义参数不为空,开始替换:" + quartzJobInfo.getParams());
                    DynamicParams(map, quartzJobInfo, etlTaskInfo, null, null, null);
                }

                //获取数据源信息
                String data_sources_choose_input = etlTaskInfo.getData_sources_choose_input();
                DataSourcesInfo dataSourcesInfoInput = dataSourcesServiceImpl.selectById(data_sources_choose_input);
                if (dataSourcesInfoInput.getData_source_type().equals("外部上传")) {
                    //获取文件服务器信息 配置到数据源选项
                    ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(dataSourcesInfoInput.getOwner());
                    if (zdhNginx != null && !zdhNginx.getHost().equals("")) {
                        dataSourcesInfoInput.setUrl(zdhNginx.getHost() + ":" + zdhNginx.getPort());
                        dataSourcesInfoInput.setUsername(zdhNginx.getUsername());
                        dataSourcesInfoInput.setPassword(zdhNginx.getPassword());
                    }
                }


                zdhMoreInfo.setZdhMoreInfo(dataSourcesInfoInput, etlTaskInfo, dataSourcesInfoOutput, quartzJobInfo, etlMoreTaskInfo);
            }


            return zdhMoreInfo;
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    public static ZdhSqlInfo create_zhdSqlInfo(QuartzJobInfo quartzJobInfo, QuartzJobMapper quartzJobMapper,
                                               SqlTaskMapper sqlTaskMapper, DataSourcesServiceImpl dataSourcesServiceImpl, ZdhNginxMapper zdhNginxMapper) {

        try {
            JSONObject json = new JSONObject();
            String date = DateUtil.formatTime(quartzJobInfo.getLast_time());
            json.put("ETL_DATE", date);
            logger.info(" JOB ,SQL,处理当前日期,传递参数ETL_DATE 为" + date);
            quartzJobInfo.setParams(json.toJSONString());

            String etl_task_id = quartzJobInfo.getEtl_task_id();
            //获取etl 任务信息
            SqlTaskInfo sqlTaskInfo = sqlTaskMapper.selectByPrimaryKey(etl_task_id);

            Map<String, Object> map = (Map<String, Object>) JSON.parseObject(quartzJobInfo.getParams());
            //此处做参数匹配转换
            if (map != null) {
                logger.info("SQL,自定义参数不为空,开始替换:" + quartzJobInfo.getParams());
                //System.out.println("自定义参数不为空,开始替换:" + dti.getParams());
                DynamicParams(map, quartzJobInfo, null, sqlTaskInfo, null, null);
            }

            //获取数据源信息
            String data_sources_choose_input = sqlTaskInfo.getData_sources_choose_input();
            String data_sources_choose_output = sqlTaskInfo.getData_sources_choose_output();
            DataSourcesInfo dataSourcesInfoInput = new DataSourcesInfo();
            if (data_sources_choose_input != null) {
                dataSourcesInfoInput = dataSourcesServiceImpl.selectById(data_sources_choose_input);
            }
            DataSourcesInfo dataSourcesInfoOutput = null;
            if (data_sources_choose_input == null || !data_sources_choose_input.equals(data_sources_choose_output)) {
                dataSourcesInfoOutput = dataSourcesServiceImpl.selectById(data_sources_choose_output);
            } else {
                dataSourcesInfoOutput = dataSourcesInfoInput;
            }


            if (dataSourcesInfoOutput.getData_source_type().equals("外部下载")) {
                //获取文件服务器信息 配置到数据源选项
                ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(dataSourcesInfoOutput.getOwner());
                if (zdhNginx != null && !zdhNginx.getHost().equals("")) {
                    dataSourcesInfoOutput.setUrl(zdhNginx.getHost() + ":" + zdhNginx.getPort());
                    dataSourcesInfoOutput.setUsername(zdhNginx.getUsername());
                    dataSourcesInfoOutput.setPassword(zdhNginx.getPassword());
                    if (sqlTaskInfo.getData_sources_params_output() != null && !sqlTaskInfo.getData_sources_params_output().trim().equals("")) {
                        JSONObject jsonObject = JSON.parseObject(sqlTaskInfo.getData_sources_params_output());
                        jsonObject.put("root_path", zdhNginx.getNginx_dir() + "/" + getUser().getId());
                        sqlTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    } else {
                        JSONObject jsonObject = new JSONObject();
                        jsonObject.put("root_path", zdhNginx.getNginx_dir() + "/" + getUser().getId());
                        sqlTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    }
                } else {
                    if (sqlTaskInfo.getData_sources_params_output() != null && !sqlTaskInfo.getData_sources_params_output().trim().equals("")) {
                        JSONObject jsonObject = JSON.parseObject(sqlTaskInfo.getData_sources_params_output());
                        jsonObject.put("root_path", zdhNginx.getTmp_dir() + "/" + getUser().getId());
                        sqlTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    } else {
                        JSONObject jsonObject = new JSONObject();
                        jsonObject.put("root_path", zdhNginx.getTmp_dir() + "/" + getUser().getId());
                        sqlTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    }
                }
            }

            ZdhSqlInfo zdhSqlInfo = new ZdhSqlInfo();
            zdhSqlInfo.setZdhInfo(dataSourcesInfoInput, sqlTaskInfo, dataSourcesInfoOutput, quartzJobInfo);

            return zdhSqlInfo;

        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }

    }

    public static ZdhSshInfo create_zhdSshInfo(QuartzJobInfo quartzJobInfo, QuartzJobMapper quartzJobMapper,
                                               SshTaskMapper sshTaskMapper, ZdhNginxMapper zdhNginxMapper) {

        try {

            JarFileMapper jarFileMapper = (JarFileMapper) SpringContext.getBean("jarFileMapper");
            JSONObject json = new JSONObject();
            String date = DateUtil.formatTime(quartzJobInfo.getLast_time());
            json.put("ETL_DATE", date);
            logger.info(" JOB ,外部JAR,处理当前日期,传递参数ETL_DATE 为" + date);
            quartzJobInfo.setParams(json.toJSONString());

            String etl_task_id = quartzJobInfo.getEtl_task_id();
            //获取etl 任务信息
            SshTaskInfo sshTaskInfo = sshTaskMapper.selectByPrimaryKey(etl_task_id);

            Map<String, Object> map = (Map<String, Object>) JSON.parseObject(quartzJobInfo.getParams());
            //此处做参数匹配转换
            if (map != null) {
                logger.info("JAR,自定义参数不为空,开始替换:" + quartzJobInfo.getParams());
                //System.out.println("自定义参数不为空,开始替换:" + dti.getParams());
                DynamicParams(map, quartzJobInfo, null, null, null, sshTaskInfo);
            }
            ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(sshTaskInfo.getOwner());
            List<JarFileInfo> jarFileInfos = jarFileMapper.selectByParams2(sshTaskInfo.getOwner(), new String[]{sshTaskInfo.getId()});

            ZdhSshInfo zdhSshInfo = new ZdhSshInfo();
            zdhSshInfo.setZdhInfo(sshTaskInfo, quartzJobInfo,zdhNginx,jarFileInfos);

            return zdhSshInfo;

        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }

    }


    public static ZdhDroolsInfo create_zdhDroolsInfo(QuartzJobInfo quartzJobInfo, QuartzJobMapper quartzJobMapper,
                                                     EtlTaskService etlTaskService, DataSourcesServiceImpl dataSourcesServiceImpl, ZdhNginxMapper zdhNginxMapper, EtlDroolsTaskMapper etlDroolsTaskMapper,
                                                     EtlMoreTaskMapper etlMoreTaskMapper,SqlTaskMapper sqlTaskMapper) throws Exception {
        try {
            JSONObject json = new JSONObject();
            String date = DateUtil.formatTime(quartzJobInfo.getLast_time());
            json.put("ETL_DATE", date);
            logger.info(" JOB ,Drools,处理当前日期,传递参数ETL_DATE 为" + date);
            quartzJobInfo.setParams(json.toJSONString());

            String etl_task_id = quartzJobInfo.getEtl_task_id();

            //获取Drools任务id
            EtlDroolsTaskInfo etlDroolsTaskInfo = etlDroolsTaskMapper.selectByPrimaryKey(etl_task_id);

            ZdhDroolsInfo zdhDroolsInfo = new ZdhDroolsInfo();

            //获取最终输出数据源
            String data_sources_choose_output = etlDroolsTaskInfo.getData_sources_choose_output();
            DataSourcesInfo dataSourcesInfoOutput = dataSourcesServiceImpl.selectById(data_sources_choose_output);

            if (dataSourcesInfoOutput.getData_source_type().equals("外部下载")) {
                //获取文件服务器信息 配置到数据源选项
                ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(dataSourcesInfoOutput.getOwner());
                if (zdhNginx != null && !zdhNginx.getHost().equals("")) {
                    dataSourcesInfoOutput.setUrl(zdhNginx.getHost() + ":" + zdhNginx.getPort());
                    dataSourcesInfoOutput.setUsername(zdhNginx.getUsername());
                    dataSourcesInfoOutput.setPassword(zdhNginx.getPassword());
                    if (etlDroolsTaskInfo.getData_sources_params_output() != null && !etlDroolsTaskInfo.getData_sources_params_output().trim().equals("")) {
                        JSONObject jsonObject = JSON.parseObject(etlDroolsTaskInfo.getData_sources_params_output());
                        jsonObject.put("root_path", zdhNginx.getNginx_dir() + "/" + getUser().getId());
                        etlDroolsTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    } else {
                        JSONObject jsonObject = new JSONObject();
                        jsonObject.put("root_path", zdhNginx.getNginx_dir() + "/" + getUser().getId());
                        etlDroolsTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    }
                } else {
                    if (etlDroolsTaskInfo.getData_sources_params_output() != null && !etlDroolsTaskInfo.getData_sources_params_output().trim().equals("")) {
                        JSONObject jsonObject = JSON.parseObject(etlDroolsTaskInfo.getData_sources_params_output());
                        jsonObject.put("root_path", zdhNginx.getTmp_dir() + "/" + getUser().getId());
                        etlDroolsTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    } else {
                        JSONObject jsonObject = new JSONObject();
                        jsonObject.put("root_path", zdhNginx.getTmp_dir() + "/" + getUser().getId());
                        etlDroolsTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    }
                }
            }


            if(etlDroolsTaskInfo.getMore_task().equalsIgnoreCase("单源ETL")){
                //解析Drools任务中的单任务
                String etl_id = etlDroolsTaskInfo.getEtl_id();
                //获取etl 任务信息
                EtlTaskInfo etlTaskInfo = etlTaskService.selectById(etl_id);

                zdhDroolsInfo.setEtlDroolsTaskInfo(etlDroolsTaskInfo);

                Map<String, Object> map = (Map<String, Object>) JSON.parseObject(quartzJobInfo.getParams());
                //此处做参数匹配转换

                if (map != null) {
                    logger.info("多源,自定义参数不为空,开始替换:" + quartzJobInfo.getParams());
                    DynamicParams(map, quartzJobInfo, etlTaskInfo, null, null, null);
                }

                //获取数据源信息
                String data_sources_choose_input = etlTaskInfo.getData_sources_choose_input();
                DataSourcesInfo dataSourcesInfoInput = dataSourcesServiceImpl.selectById(data_sources_choose_input);
                if (dataSourcesInfoInput.getData_source_type().equals("外部上传")) {
                    //获取文件服务器信息 配置到数据源选项
                    ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(dataSourcesInfoInput.getOwner());
                    if (zdhNginx != null && !zdhNginx.getHost().equals("")) {
                        dataSourcesInfoInput.setUrl(zdhNginx.getHost() + ":" + zdhNginx.getPort());
                        dataSourcesInfoInput.setUsername(zdhNginx.getUsername());
                        dataSourcesInfoInput.setPassword(zdhNginx.getPassword());
                    }
                }
                zdhDroolsInfo.setZdhDroolsInfo(dataSourcesInfoInput, etlTaskInfo, dataSourcesInfoOutput, quartzJobInfo, etlDroolsTaskInfo);
            }

            if(etlDroolsTaskInfo.getMore_task().equalsIgnoreCase("多源ETL")){
                //获取多源任务id
                EtlMoreTaskInfo etlMoreTaskInfo = etlMoreTaskMapper.selectByPrimaryKey(etlDroolsTaskInfo.getEtl_id());
                zdhDroolsInfo.setEtlMoreTaskInfo(etlMoreTaskInfo);

                //解析多源任务中的单任务
                String[] etl_ids = etlMoreTaskInfo.getEtl_ids().split(",");
                //获取etl 任务信息
                List<EtlTaskInfo> etlTaskInfos = etlTaskService.selectByIds(etl_ids);

                for(EtlTaskInfo etlTaskInfo:etlTaskInfos){
                    Map<String, Object> map = (Map<String, Object>) JSON.parseObject(quartzJobInfo.getParams());
                    //此处做参数匹配转换
                    if (map != null) {
                        logger.info("多源,自定义参数不为空,开始替换:" + quartzJobInfo.getParams());
                        DynamicParams(map, quartzJobInfo, etlTaskInfo, null, null, null);
                    }

                    //获取数据源信息
                    String data_sources_choose_input = etlTaskInfo.getData_sources_choose_input();
                    DataSourcesInfo dataSourcesInfoInput = dataSourcesServiceImpl.selectById(data_sources_choose_input);
                    if (dataSourcesInfoInput.getData_source_type().equals("外部上传")) {
                        //获取文件服务器信息 配置到数据源选项
                        ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(dataSourcesInfoInput.getOwner());
                        if (zdhNginx != null && !zdhNginx.getHost().equals("")) {
                            dataSourcesInfoInput.setUrl(zdhNginx.getHost() + ":" + zdhNginx.getPort());
                            dataSourcesInfoInput.setUsername(zdhNginx.getUsername());
                            dataSourcesInfoInput.setPassword(zdhNginx.getPassword());
                        }
                    }
                    zdhDroolsInfo.setZdhDroolsInfo(dataSourcesInfoInput, etlTaskInfo, dataSourcesInfoOutput, quartzJobInfo, etlDroolsTaskInfo);
                }

            }
            if(etlDroolsTaskInfo.getMore_task().equalsIgnoreCase("SQL")){

                //获取etl 任务信息
                SqlTaskInfo sqlTaskInfo = sqlTaskMapper.selectByPrimaryKey(etlDroolsTaskInfo.getEtl_id());

                if(sqlTaskInfo==null){
                    throw new Exception("无法找到对应的SQL任务");
                }

                Map<String, Object> map = (Map<String, Object>) JSON.parseObject(quartzJobInfo.getParams());
                //此处做参数匹配转换
                if (map != null) {
                    logger.info("SQL,自定义参数不为空,开始替换:" + quartzJobInfo.getParams());
                    DynamicParams(map, quartzJobInfo, null, sqlTaskInfo, null, null);
                }

                zdhDroolsInfo.setSqlTaskInfo(sqlTaskInfo);
                zdhDroolsInfo.setQuartzJobInfo(quartzJobInfo);
            }

            //设置drool 任务信息
            zdhDroolsInfo.setEtlDroolsTaskInfo(etlDroolsTaskInfo);
            //设置drool 输出数据源信息
            zdhDroolsInfo.setDsi_Info(dataSourcesInfoOutput);


            return zdhDroolsInfo;
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    public static ZdhJarInfo create_zhdJarInfo(QuartzJobInfo quartzJobInfo, QuartzJobMapper quartzJobMapper,
                                               JarTaskMapper jarTaskMapper, ZdhNginxMapper zdhNginxMapper) {

        try {
            JarFileMapper jarFileMapper = (JarFileMapper) SpringContext.getBean("jarFileMapper");
            JSONObject json = new JSONObject();
            String date = DateUtil.formatTime(quartzJobInfo.getLast_time());
            json.put("ETL_DATE", date);
            logger.info(" JOB ,外部JAR,处理当前日期,传递参数ETL_DATE 为" + date);
            quartzJobInfo.setParams(json.toJSONString());

            String etl_task_id = quartzJobInfo.getEtl_task_id();
            //获取etl 任务信息
            JarTaskInfo jarTaskInfo = jarTaskMapper.selectByPrimaryKey(etl_task_id);

            Map<String, Object> map = (Map<String, Object>) JSON.parseObject(quartzJobInfo.getParams());
            //此处做参数匹配转换
            if (map != null) {
                logger.info("JAR,自定义参数不为空,开始替换:" + quartzJobInfo.getParams());
                //System.out.println("自定义参数不为空,开始替换:" + dti.getParams());
                DynamicParams(map, quartzJobInfo, null, null, jarTaskInfo, null);
            }

            //获取文件服务器信息 配置到数据源选项
            ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(jarTaskInfo.getOwner());
            List<JarFileInfo> jarFileInfos = jarFileMapper.selectByParams2(jarTaskInfo.getOwner(), new String[]{jarTaskInfo.getId()});

            ZdhJarInfo zdhJarInfo = new ZdhJarInfo();
            zdhJarInfo.setZdhInfo(jarTaskInfo, quartzJobInfo, zdhNginx, jarFileInfos);

            return zdhJarInfo;

        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }

    }


    public static void DynamicParams(Map<String, Object> map, QuartzJobInfo quartzJobInfo, EtlTaskInfo etlTaskInfo, SqlTaskInfo sqlTaskInfo, JarTaskInfo jarTaskInfo, SshTaskInfo sshTaskInfo) {
        try {
            String date_nodash = DateUtil.formatNodash(quartzJobInfo.getLast_time());
            String date_time = DateUtil.formatTime(quartzJobInfo.getLast_time());
            String date_dt = DateUtil.format(quartzJobInfo.getLast_time());
            Map<String,Object> jinJavaParam=new HashMap<>();
            jinJavaParam.put("zdh.date.nodash", date_nodash);
            jinJavaParam.put("zdh.date.time", date_time);
            jinJavaParam.put("zdh.date", date_dt);
            Jinjava jj=new Jinjava();

            map.forEach((k, v) -> {
                logger.info("key:" + k + ",value:" + v);
                jinJavaParam.put(k,v);
            });

            if (etlTaskInfo != null) {
                final String filter = jj.render(etlTaskInfo.getData_sources_filter_input(),jinJavaParam);
                final String clear = jj.render(etlTaskInfo.getData_sources_clear_output(),jinJavaParam);
                etlTaskInfo.setData_sources_filter_input(filter);
                etlTaskInfo.setData_sources_clear_output(clear);
            }

            if (sqlTaskInfo != null) {
                final String etl_sql = jj.render(sqlTaskInfo.getEtl_sql(),jinJavaParam);
                final String clear = jj.render(sqlTaskInfo.getData_sources_clear_output(),jinJavaParam);
                sqlTaskInfo.setEtl_sql(etl_sql);
                sqlTaskInfo.setData_sources_clear_output(clear);
            }

            if (jarTaskInfo != null) {
                final String spark_submit_params = jj.render(jarTaskInfo.getSpark_submit_params(),jinJavaParam);
                jarTaskInfo.setSpark_submit_params(spark_submit_params);
            }

            if (sshTaskInfo != null) {
                final String script_path = jj.render(sshTaskInfo.getSsh_script_path(),jinJavaParam);
                sshTaskInfo.setSsh_script_path(script_path);

                jinJavaParam.put("zdh_online_file", sshTaskInfo.getSsh_script_path()+"/"+sshTaskInfo.getId()+"_online");
                final String ssh_cmd = jj.render(sshTaskInfo.getSsh_cmd(),jinJavaParam);
                sshTaskInfo.setSsh_cmd(ssh_cmd);

                final String script_context = jj.render(sshTaskInfo.getSsh_script_context(),jinJavaParam);
                sshTaskInfo.setSsh_script_context(script_context);

            }
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }


    }


    /**
     * 获取后台url
     *
     * @param zdhHaInfoMapper
     * @return
     */
    public static ZdhHaInfo getZdhUrl(ZdhHaInfoMapper zdhHaInfoMapper) {
        logger.info("获取后台处理URL");
        String url = "http://127.0.0.1:60001/api/v1/zdh";
        List<ZdhHaInfo> zdhHaInfoList = zdhHaInfoMapper.selectByStatus("enabled");
        String id="-1";
        if (zdhHaInfoList != null && zdhHaInfoList.size() >= 1) {
            int random=new Random().nextInt(zdhHaInfoList.size());
            return zdhHaInfoList.get(random);
        }
        ZdhHaInfo zdhHaInfo=new ZdhHaInfo();
        zdhHaInfo.setId(id);
        zdhHaInfo.setZdh_url(url);
        return zdhHaInfo;
    }


    /**
     * 插入日志
     *
     * @param quartzJobInfo
     * @param level
     * @param msg
     */
    public static void insertLog(QuartzJobInfo quartzJobInfo ,String level, String msg) {

        ZdhLogs zdhLogs = new ZdhLogs();
        zdhLogs.setJob_id(quartzJobInfo.getJob_id());
        Timestamp lon_time = new Timestamp(new Date().getTime());
        zdhLogs.setTask_logs_id(quartzJobInfo.getTask_log_id());
        zdhLogs.setLog_time(lon_time);
        zdhLogs.setMsg(msg);
        zdhLogs.setLevel(level.toUpperCase());
        //linkedBlockingDeque.add(zdhLogs);
        ZdhLogsService zdhLogsService = (ZdhLogsService) SpringContext.getBean("zdhLogsServiceImpl");
        zdhLogsService.insert(zdhLogs);
    }


    public static Boolean sendZdh(String task_logs_id, String model_log, Boolean exe_status, QuartzJobInfo quartzJobInfo) {
        logger.info("开始发送信息到zdh处理引擎");
        QuartzJobMapper quartzJobMapper = (QuartzJobMapper) SpringContext.getBean("quartzJobMapper");
        EtlTaskService etlTaskService = (EtlTaskService) SpringContext.getBean("etlTaskServiceImpl");
        DataSourcesServiceImpl dataSourcesServiceImpl = (DataSourcesServiceImpl) SpringContext.getBean("dataSourcesServiceImpl");
        ZdhLogsService zdhLogsService = (ZdhLogsService) SpringContext.getBean("zdhLogsServiceImpl");
        ZdhHaInfoMapper zdhHaInfoMapper = (ZdhHaInfoMapper) SpringContext.getBean("zdhHaInfoMapper");
        TaskLogsMapper taskLogsMapper = (TaskLogsMapper) SpringContext.getBean("taskLogsMapper");
        ZdhNginxMapper zdhNginxMapper = (ZdhNginxMapper) SpringContext.getBean("zdhNginxMapper");
        EtlMoreTaskMapper etlMoreTaskMapper = (EtlMoreTaskMapper) SpringContext.getBean("etlMoreTaskMapper");
        SqlTaskMapper sqlTaskMapper = (SqlTaskMapper) SpringContext.getBean("sqlTaskMapper");
        JarTaskMapper jarTaskMapper = (JarTaskMapper) SpringContext.getBean("jarTaskMapper");
        EtlDroolsTaskMapper etlDroolsTaskMapper = (EtlDroolsTaskMapper) SpringContext.getBean("etlDroolsTaskMapper");
        SshTaskMapper sshTaskMapper = (SshTaskMapper) SpringContext.getBean("sshTaskMapper");

        String params = quartzJobInfo.getParams().trim();
        ZdhHaInfo zdhHaInfo = getZdhUrl(zdhHaInfoMapper);
        String url=zdhHaInfo.getZdh_url();
        JSONObject json = new JSONObject();
        if (!params.equals("")) {
            logger.info(model_log + " JOB ,参数不为空判断是否有url 参数");
            String value = JSON.parseObject(params).getString("url");
            if (value != null && !value.equals("")) {
                url = value;
            }
            json = JSON.parseObject(params);
        }
        System.out.println("========fdsfsf=========" + quartzJobInfo.getLast_time());
        String date = DateUtil.formatTime(quartzJobInfo.getLast_time());
        json.put("ETL_DATE", date);
        logger.info(model_log + " JOB ,处理当前日期,传递参数ETL_DATE 为" + date);
        quartzJobInfo.setParams(json.toJSONString());

        logger.info(model_log + " JOB ,获取当前的[url]:" + url);

        ZdhMoreInfo zdhMoreInfo = new ZdhMoreInfo();
        ZdhInfo zdhInfo = new ZdhInfo();
        ZdhSqlInfo zdhSqlInfo = new ZdhSqlInfo();
        ZdhJarInfo zdhJarInfo = new ZdhJarInfo();
        ZdhDroolsInfo zdhDroolsInfo = new ZdhDroolsInfo();
        ZdhSshInfo zdhSshInfo = new ZdhSshInfo();
        TaskLogs taskLogs = taskLogsMapper.selectByPrimaryKey(task_logs_id);
        try {
            if (quartzJobInfo.getMore_task().equals("多源ETL")) {
                logger.info("组装多源ETL任务信息");
                zdhMoreInfo = create_more_task_zdhInfo(quartzJobInfo, quartzJobMapper, etlTaskService, dataSourcesServiceImpl, zdhNginxMapper, etlMoreTaskMapper);
            } else if (quartzJobInfo.getMore_task().equals("单源ETL")) {
                logger.info("组装单源ETL任务信息");
                zdhInfo = create_zhdInfo(quartzJobInfo, quartzJobMapper, etlTaskService, dataSourcesServiceImpl, zdhNginxMapper, etlMoreTaskMapper);
            } else if (quartzJobInfo.getMore_task().equalsIgnoreCase("SQL")) {
                logger.info("组装SQL任务信息");
                zdhSqlInfo = create_zhdSqlInfo(quartzJobInfo, quartzJobMapper, sqlTaskMapper, dataSourcesServiceImpl, zdhNginxMapper);
            } else if (quartzJobInfo.getMore_task().equalsIgnoreCase("外部JAR")) {
                logger.info("组装外部JAR任务信息");
                zdhJarInfo = create_zhdJarInfo(quartzJobInfo, quartzJobMapper, jarTaskMapper, zdhNginxMapper);
            } else if (quartzJobInfo.getMore_task().equalsIgnoreCase("Drools")) {
                logger.info("组装Drools任务信息");
                zdhDroolsInfo = create_zdhDroolsInfo(quartzJobInfo, quartzJobMapper, etlTaskService, dataSourcesServiceImpl, zdhNginxMapper, etlDroolsTaskMapper,etlMoreTaskMapper,sqlTaskMapper);
            } else if (quartzJobInfo.getMore_task().equalsIgnoreCase("SSH")) {
                logger.info("组装SSH任务信息");
                zdhSshInfo = create_zhdSshInfo(quartzJobInfo, quartzJobMapper, sshTaskMapper, zdhNginxMapper);
            }

            if (exe_status == true) {
                logger.info(model_log + " JOB ,开始发送ETL处理请求");
                zdhInfo.setTask_logs_id(task_logs_id);
                zdhMoreInfo.setTask_logs_id(task_logs_id);
                zdhSqlInfo.setTask_logs_id(task_logs_id);
                zdhJarInfo.setTask_logs_id(task_logs_id);
                zdhDroolsInfo.setTask_logs_id(task_logs_id);
                zdhSshInfo.setTask_logs_id(task_logs_id);
                insertLog(quartzJobInfo, "DEBUG", "[调度平台]:" + model_log + " JOB ,开始发送ETL处理请求");
                taskLogs.setEtl_date(date);
                taskLogs.setProcess("10");
                taskLogs.setUpdate_time(new Timestamp(new Date().getTime()));
                updateTaskLog(taskLogs, taskLogsMapper);
                String executor = zdhHaInfo.getId();
                String url_tmp = "";
                String etl_info = "";
                if (quartzJobInfo.getMore_task().equals("多源ETL")) {
                    url_tmp = url + "/more";
                    etl_info = JSON.toJSONString(zdhMoreInfo);
                } else if (quartzJobInfo.getMore_task().equals("单源ETL")) {
                    url_tmp = url;
                    etl_info = JSON.toJSONString(zdhInfo);
                } else if (quartzJobInfo.getMore_task().equals("SQL")) {
                    url_tmp = url + "/sql";
                    etl_info = JSON.toJSONString(zdhSqlInfo);
                } else if (quartzJobInfo.getMore_task().equals("外部JAR")) {
                    logger.info("[调度平台]:外部JAR,参数:" + JSON.toJSONString(zdhJarInfo));
                    insertLog(quartzJobInfo, "DEBUG", "[调度平台]:外部JAR,参数:" + JSON.toJSONString(zdhJarInfo));
                    submit_jar(quartzJobInfo, zdhJarInfo);
                } else if (quartzJobInfo.getMore_task().equals("Drools")) {
                    url_tmp = url + "/drools";
                    etl_info = JSON.toJSONString(zdhDroolsInfo);
                } else if (quartzJobInfo.getMore_task().equalsIgnoreCase("SSH")) {
                    logger.info("[调度平台]:SSH,参数:" + JSON.toJSONString(zdhSshInfo));
                    insertLog(quartzJobInfo, "DEBUG", "[调度平台]:SSH,参数:" + JSON.toJSONString(zdhSshInfo));
                    quartzJobInfo.setLast_status("etl");
                    boolean rs = ssh_exec(quartzJobInfo, zdhSshInfo);
                    if (rs) {
                        quartzJobInfo.setLast_status("finish");
                        //此处是按照同步方式设计的,如果执行的命令是异步命令那些需要用户自己维护这个状态
                        taskLogs.setStatus(InstanceStatus.FINISH.getValue());
                        taskLogs.setProcess("100");
                        taskLogs.setUpdate_time(new Timestamp(new Date().getTime()));
                        updateTaskLog(taskLogs, taskLogsMapper);
                    }
                    return rs;
                }

                taskLogs.setExecutor(executor);
                taskLogs.setUrl(url_tmp);
                taskLogs.setEtl_info(etl_info);
                taskLogs.setHistory_server(zdhHaInfo.getHistory_server());
                taskLogs.setMaster(zdhHaInfo.getMaster());
                taskLogs.setApplication_id(zdhHaInfo.getApplication_id());
                taskLogs.setUpdate_time(new Timestamp(new Date().getTime()));
                updateTaskLog(taskLogs, taskLogsMapper);

                if (!quartzJobInfo.getMore_task().equalsIgnoreCase("SSH") ) {
                    logger.info("[调度平台]:" + url_tmp + " ,参数:" + etl_info);
                    insertLog(quartzJobInfo, "DEBUG", "[调度平台]:" + url_tmp + " ,参数:" + etl_info);
                    HttpUtil.postJSON(url_tmp, etl_info);
                    logger.info(model_log + " JOB ,更新调度任务状态为etl");
                    quartzJobInfo.setLast_status("etl");
                    taskLogs.setStatus(InstanceStatus.ETL.getValue());
                    taskLogs.setProcess("15");
                    taskLogsMapper.updateTaskLogsById3(taskLogs);
                    //updateTaskLog(taskLogs, taskLogsMapper);
                }

            }
        } catch (Exception e) {
            e.printStackTrace();
            logger.info("[调度平台]:" + model_log + " JOB ,开始发送" + quartzJobInfo.getMore_task() + " 处理请求,异常请检查zdh_server服务是否正常运行,或者检查网络情况" + e.getMessage());
            insertLog(quartzJobInfo, "ERROR", "[调度平台]:" + model_log + " JOB ,开始发送" + quartzJobInfo.getMore_task() + " 处理请求,异常请检查zdh_server服务是否正常运行,或者检查网络情况" + e.getMessage());
            taskLogs.setStatus(InstanceStatus.ERROR.getValue());
            taskLogs.setProcess("17");
            taskLogs.setUpdate_time(new Timestamp(new Date().getTime()));
            updateTaskLog(taskLogs, taskLogsMapper);
            //更新执行状态为error
            logger.info(model_log + " JOB ,更新调度任务状态为error");
            quartzJobInfo.setLast_status("error");
            logger.error(e.getMessage());
            exe_status = false;
        }

        return exe_status;

    }

    public static void submit_jar(QuartzJobInfo quartzJobInfo, ZdhJarInfo zdhJarInfo) throws IOException {
        try {
            logger.info("[调度平台]:外部JAR,开始提交jar 任务");
            insertLog(zdhJarInfo.getQuartzJobInfo(), "DEBUG", "[调度平台]:外部JAR,开始提交jar 任务");
            String system = System.getProperty("os.name");
            String[] str = zdhJarInfo.getJarTaskInfo().getSpark_submit_params().split("\r\n|\n");
            String newcommand = "";
            String line = System.getProperty("line.separator");
            for (String s : str) {
                newcommand = newcommand + s + line;
            }

            String fileName = zdhJarInfo.getJarTaskInfo().getEtl_context();
            if (system.toLowerCase().startsWith("win")) {
                fileName = fileName + ".bat";
            } else {
                fileName = fileName + ".sh";
            }

            File file = new File("spark_submit/" + quartzJobInfo.getJob_id());
            if (!file.exists()) {
                file.mkdirs();
            }

            File file2 = new File("spark_submit/" + quartzJobInfo.getJob_id() + "/" + fileName);
            if (file2.exists()) {
                file2.delete();
            }
            file2.createNewFile();

            Map<String,Object> jinJavaParam=new HashMap<>();
            jinJavaParam.put("zdh_jar_path", "spark_submit/" + quartzJobInfo.getJob_id());
            Jinjava jj=new Jinjava();

            newcommand = jj.render(newcommand,jinJavaParam);
            logger.info("[调度平台]:外部JAR,生成脚本临时文件:" + file2.getAbsolutePath());
            insertLog(zdhJarInfo.getQuartzJobInfo(), "DEBUG", "[调度平台]:外部JAR,生成脚本临时文件:" + file2.getAbsolutePath());
            logger.info("[调度平台]:外部JAR,脚本内容:" + line + newcommand);
            insertLog(zdhJarInfo.getQuartzJobInfo(), "DEBUG", "[调度平台]:外部JAR,脚本内容:" + line + newcommand);
            BufferedWriter fileWritter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file2.getAbsolutePath(), true), "UTF-8"));
            fileWritter.write(newcommand);
            fileWritter.close();

            List<JarFileInfo> jarFileInfos = zdhJarInfo.getJarFileInfos();

            for (JarFileInfo jarFileInfo : jarFileInfos) {
                //检查文件是否存在

                File file_jar = new File("spark_submit/" + quartzJobInfo.getJob_id() + "/" + jarFileInfo.getFile_name());
                if (file_jar.exists()) {
                    file_jar.delete();
                }
                logger.info("[调度平台]:外部JAR,开始下载文件:" + jarFileInfo.getFile_name());
                ZdhNginx zdhNginx = zdhJarInfo.getZdhNginx();
                //下载文件
                if (zdhNginx.getHost() != null && !zdhNginx.getHost().equals("")) {
                    logger.info("开始下载文件:SFTP方式" + jarFileInfo.getFile_name());
                    insertLog(zdhJarInfo.getQuartzJobInfo(), "DEBUG", "[调度平台]:外部JAR,开始下载文件:SFTP方式" + jarFileInfo.getFile_name());
                    //连接sftp 下载
                    SFTPUtil sftp = new SFTPUtil(zdhNginx.getUsername(), zdhNginx.getPassword(),
                            zdhNginx.getHost(), new Integer(zdhNginx.getPort()));
                    sftp.login();
                    sftp.download(zdhNginx.getNginx_dir() + "/" + zdhNginx.getOwner(), jarFileInfo.getFile_name(), "spark_submit/" + quartzJobInfo.getJob_id() + "/" + jarFileInfo.getFile_name());
                } else {
                    logger.info("开始下载文件:本地方式" + jarFileInfo.getFile_name());
                    insertLog(zdhJarInfo.getQuartzJobInfo(), "DEBUG", "[调度平台]:外部JAR,开始下载文件:本地方式" + jarFileInfo.getFile_name());
                    //本地文件
                    BufferedOutputStream bos = null;
                    FileInputStream in = null;
                    try {
                        in = new FileInputStream(zdhNginx.getTmp_dir() + "/" + zdhNginx.getOwner() + "/" + jarFileInfo.getFile_name());
                        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
                        System.out.println("bytes available:" + in.available());
                        byte[] temp = new byte[1024];
                        int size = 0;
                        while ((size = in.read(temp)) != -1) {
                            out.write(temp, 0, size);
                        }
                        byte[] bytes = out.toByteArray();
                        System.out.println("bytes size got is:" + bytes.length);

                        bos = new BufferedOutputStream(new FileOutputStream("spark_submit/" + quartzJobInfo.getJob_id() + "/" + jarFileInfo.getFile_name(), true));
                        bos.write(bytes);
                        bos.flush();
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        if (in != null) in.close();
                        if (bos != null) bos.close();
                    }

                }

            }

            //执行脚本
            logger.info("当前系统为：" + system + ",运行脚本:" + file2.getAbsolutePath());
            insertLog(zdhJarInfo.getQuartzJobInfo(), "DEBUG", "[调度平台]:外部JAR,当前系统为:" + system + ",运行脚本:" + file2.getAbsolutePath() + ",请耐心等待jar 任务开始执行....");
            long t1 = System.currentTimeMillis();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream error = new ByteArrayOutputStream();
            if (system.toLowerCase().startsWith("win")) {
                CommandUtils.exeCommand("cmd.exe /C  " + file2.getAbsolutePath(), out, error);
            } else {
                CommandUtils.exeCommand("sh " + file2.getAbsolutePath(), out, error);
            }
            long t2 = System.currentTimeMillis();

            for (String li : out.toString(CommandUtils.DEFAULT_CHARSET).split("\r\n|\n")) {
                insertLog(zdhJarInfo.getQuartzJobInfo(), "DEBUG", li);
            }
            for (String li : error.toString(CommandUtils.DEFAULT_CHARSET).split("\r\n|\n")) {
                insertLog(zdhJarInfo.getQuartzJobInfo(), "ERROR", li);
            }
            out.close();
            error.close();
            insertLog(zdhJarInfo.getQuartzJobInfo(), "DEBUG", "[调度平台]:外部JAR,jar 任务执行结束,耗时:" + (t2 - t1) / 1000 + "s");


        } catch (Exception e) {
            e.printStackTrace();
        }


    }

    public static boolean ssh_exec(QuartzJobInfo quartzJobInfo, ZdhSshInfo zdhSshInfo) throws IOException, JSchException, SftpException {
        try {
            String system = System.getProperty("os.name");
            long t1 = System.currentTimeMillis();
            insertLog(zdhSshInfo.getQuartzJobInfo(), "DEBUG", "[调度平台]:SSH,当前系统为:" + system + ",请耐心等待SSH任务开始执行....");
            String host = zdhSshInfo.getSshTaskInfo().getHost();
            String port = zdhSshInfo.getSshTaskInfo().getPort();
            String username = zdhSshInfo.getSshTaskInfo().getUser_name();
            String password = zdhSshInfo.getSshTaskInfo().getPassword();
            String ssh_cmd=zdhSshInfo.getSshTaskInfo().getSsh_cmd();
            String id=zdhSshInfo.getSshTaskInfo().getId();
            String script_path=zdhSshInfo.getSshTaskInfo().getSsh_script_path();
            String script_context=zdhSshInfo.getSshTaskInfo().getSsh_script_context();
            List<JarFileInfo> jarFileInfos=zdhSshInfo.getJarFileInfos();
            ZdhNginx zdhNginx=zdhSshInfo.getZdhNginx();
            if(!script_context.isEmpty()||!jarFileInfos.isEmpty()){
                SFTPUtil sftpUtil = new SFTPUtil(username, password, host, Integer.parseInt(port));
                sftpUtil.login();
                if(!script_context.isEmpty()){
                    insertLog(zdhSshInfo.getQuartzJobInfo(), "DEBUG", "[调度平台]:SSH,发现在线脚本,使用在线脚本ssh 命令 可配合{{zdh_online_file}} 使用 example sh {{zdh_online_file}} 即是执行在线的脚本");
                    InputStream   inputStream   =   new   ByteArrayInputStream(script_context.getBytes());
                    sftpUtil.upload(script_path,id+"_online",inputStream);
                }

                if(!jarFileInfos.isEmpty()){
                   for(JarFileInfo jarFileInfo :jarFileInfos){
                       //下载文件
                       if (zdhNginx.getHost() != null && !zdhNginx.getHost().equals("")) {
                           logger.info("开始下载文件:SFTP方式" + jarFileInfo.getFile_name());
                           insertLog(zdhSshInfo.getQuartzJobInfo(), "DEBUG", "[调度平台]:SSH,开始下载文件:SFTP方式" + jarFileInfo.getFile_name());
                           //连接sftp 下载
                           SFTPUtil sftp = new SFTPUtil(zdhNginx.getUsername(), zdhNginx.getPassword(),
                                   zdhNginx.getHost(), new Integer(zdhNginx.getPort()));
                           sftp.login();
                           byte[] fileByte=sftp.download(zdhNginx.getNginx_dir() + "/" + zdhNginx.getOwner(), jarFileInfo.getFile_name());
                           sftpUtil.upload(script_path,jarFileInfo.getFile_name(),fileByte);
                           sftp.logout();
                       } else {
                           logger.info("开始下载文件:本地方式" + jarFileInfo.getFile_name());
                           insertLog(zdhSshInfo.getQuartzJobInfo(), "DEBUG", "[调度平台]:SSH,开始下载文件:本地方式" + jarFileInfo.getFile_name());
                           //本地文件

                           FileInputStream in = null;
                           try {
                               in = new FileInputStream(zdhNginx.getTmp_dir() + "/" + zdhNginx.getOwner() + "/" + jarFileInfo.getFile_name());
                               ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
                               System.out.println("bytes available:" + in.available());
                               byte[] temp = new byte[1024];
                               int size = 0;
                               while ((size = in.read(temp)) != -1) {
                                   out.write(temp, 0, size);
                               }
                               byte[] bytes = out.toByteArray();
                               System.out.println("bytes size got is:" + bytes.length);
                               sftpUtil.upload(script_path,jarFileInfo.getFile_name(),in);

                           } catch (Exception e) {
                               e.printStackTrace();
                               throw e;
                           } finally {
                               if (in != null) in.close();
                           }

                       }
                   }
                }

                sftpUtil.logout();
            }

            SSHUtil sshUtil = new SSHUtil(username, password, host, Integer.parseInt(port));
            sshUtil.login();

            insertLog(zdhSshInfo.getQuartzJobInfo(), "DEBUG", "[调度平台]:SSH,使用在线脚本,"+ssh_cmd);
            String[] result = sshUtil.exec(ssh_cmd);
            String error = result[0];
            String out = result[1];
            long t2 = System.currentTimeMillis();

            for (String li : out.split("\r\n|\n")) {
                if (!li.trim().isEmpty())
                    insertLog(zdhSshInfo.getQuartzJobInfo(), "DEBUG", li);
            }
            for (String li : error.split("\r\n|\n")) {
                if (!li.trim().isEmpty())
                    insertLog(zdhSshInfo.getQuartzJobInfo(), "ERROR", li);
            }
            insertLog(zdhSshInfo.getQuartzJobInfo(), "DEBUG", "[调度平台]:SSH,SSH任务执行结束,耗时:" + (t2 - t1) / 1000 + "s");

            if (!error.isEmpty()) {
                return false;
            }
            return true;

        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }

    }

    public static TaskLogs insertTaskLog(String task_logs_id, QuartzJobInfo quartzJobInfo, String etl_date, String status, String process, String thread_id, TaskLogsMapper taskLogsMapper) {

        Timestamp tm = new Timestamp(new Date().getTime());
        TaskLogs taskLogs = new TaskLogs();
        taskLogs.setId(task_logs_id);
        taskLogs.setJob_id(quartzJobInfo.getJob_id());
        taskLogs.setJob_context(quartzJobInfo.getJob_context());
        taskLogs.setEtl_date(etl_date);
        taskLogs.setStatus(status);
        taskLogs.setStart_time(tm);
        taskLogs.setUpdate_time(tm);
        taskLogs.setOwner(quartzJobInfo.getOwner());
        taskLogs.setProcess(process);
        taskLogs.setThread_id(thread_id);
        taskLogs.setRetry_time(Timestamp.valueOf("2000-01-01 00:00:00"));
        //taskLogs.setWeb_application_id(web_application_id);
        taskLogsMapper.insert(taskLogs);
        return taskLogs;
    }

    public static void updateTaskLog(TaskLogs taskLogs, TaskLogsMapper taskLogsMapper) {
        taskLogsMapper.updateByPrimaryKey(taskLogs);
    }

    /**
     * 更新任务日志,etl_date
     *
     * @param taskLogs
     * @param quartzJobInfo
     * @param taskLogsMapper
     */
    public static void updateTaskLogEtlDate(TaskLogs taskLogs, QuartzJobInfo quartzJobInfo, TaskLogsMapper taskLogsMapper) {
        String date = DateUtil.formatTime(quartzJobInfo.getLast_time());
        taskLogs.setEtl_date(date);
        taskLogs.setUpdate_time(new Timestamp(new Date().getTime()));
        taskLogs.setProcess("7");
        updateTaskLog(taskLogs, taskLogsMapper);
    }


    public static void updateTaskLog12(String task_logs_id, TaskLogsMapper taskLogsMapper) {
        TaskLogs taskLogs = taskLogsMapper.selectByPrimaryKey(task_logs_id);
        taskLogs.setUpdate_time(new Timestamp(new Date().getTime()));
        taskLogs.setStatus(InstanceStatus.ERROR.getValue());
        taskLogs.setProcess("12");//调度完成
        updateTaskLog(taskLogs, taskLogsMapper);
    }

    public static void updateTaskLogError(String task_logs_id, String process, TaskLogsMapper taskLogsMapper, String status, int interval_time) {
        TaskLogs taskLogs = taskLogsMapper.selectByPrimaryKey(task_logs_id);
        taskLogs.setUpdate_time(new Timestamp(new Date().getTime()));
        taskLogs.setStatus(status);//error,retry
        taskLogs.setProcess(process);//调度完成
        taskLogs.setRetry_time(DateUtil.add(new Timestamp(new Date().getTime()), Calendar.SECOND, interval_time));
        updateTaskLog(taskLogs, taskLogsMapper);
    }

    /**
     * 检查任务依赖
     *
     * @param jobType
     * @param quartzJobInfo
     * @param taskLogsMapper
     */
    public static boolean checkDep(String jobType, QuartzJobInfo quartzJobInfo, TaskLogsMapper taskLogsMapper) {

        //检查任务依赖
        if (quartzJobInfo.getJump_dep() != null && quartzJobInfo.getJump_dep().equalsIgnoreCase("on")) {
            String msg2 = "[" + jobType + "] JOB ,跳过依赖任务";
            logger.info(msg2);
            insertLog(quartzJobInfo, "INFO", msg2);
            return true;
        }
        String job_ids = quartzJobInfo.getJob_ids();
        if (job_ids != null && job_ids.split(",").length > 0) {
            for (String dep_job_id : job_ids.split(",")) {
                String etl_date = DateUtil.formatTime(quartzJobInfo.getLast_time());
                List<TaskLogs> taskLogsList = taskLogsMapper.selectByIdEtlDate(getUser().getId(), dep_job_id, etl_date);
                if (taskLogsList == null || taskLogsList.size() <= 0) {
                    String msg = "[" + jobType + "] JOB ,依赖任务" + dep_job_id + ",ETL日期" + etl_date + ",未完成";
                    logger.info(msg);
                    insertLog(quartzJobInfo, "INFO", msg);
                    return false;
                }
                String msg2 = "[" + jobType + "] JOB ,依赖任务" + dep_job_id + ",ETL日期" + etl_date + ",已完成";
                logger.info(msg2);
                insertLog(quartzJobInfo, "INFO", msg2);
            }

        }
        return true;
    }

    /**
     * 检查任务状态,并修改参数(任务执行日期等)
     *
     * @param jobType
     * @param quartzJobInfo
     * @param taskLogsMapper
     * @param quartzManager2
     * @return
     */
    public static boolean checkStatus(String jobType, QuartzJobInfo quartzJobInfo, TaskLogsMapper taskLogsMapper, QuartzManager2 quartzManager2) {
        //第一次 last_time 为空 赋值start_time
        if (quartzJobInfo.getLast_time() == null) {
            quartzJobInfo.setLast_time(quartzJobInfo.getStart_time());
        }

        //last_status 表示 finish,etl,error
        //finish 表示成功,etl 表示正在处理,error 表示失败,dispatch 表示在调度中,retry 表示重试中 wait_retry 表示等待重试
        //dispatch 状态不用检查,quartz 框架限制
        //此处需要处理调度服务死掉的情况(1 任务已发送 ,状态未改变 还是dis ，2 任务未发送，状态还是dis ,对于状态是dis 的 放行)
        if (quartzJobInfo.getLast_status() != null &&
                (quartzJobInfo.getLast_status().equals("etl") || quartzJobInfo.getLast_status().equals("wait_retry"))) {
            logger.info("[" + jobType + "] JOB ,当前任务正在处理中,任务状态:" + quartzJobInfo.getLast_status());
            insertLog(quartzJobInfo, "INFO", "[" + jobType + "] JOB ,当前任务正在处理中,任务状态:" + quartzJobInfo.getLast_status());
            return false;
        }


        //finish 状态 last_time 增加步长
        if (quartzJobInfo.getLast_status() != null && quartzJobInfo.getLast_status().equals("finish")) {
            Timestamp last = quartzJobInfo.getLast_time();
            String step_size = quartzJobInfo.getStep_size();
            int dateType = Calendar.DAY_OF_MONTH;
            int num = 1;
            if (step_size.endsWith("s")) {
                dateType = Calendar.SECOND;
                num = Integer.parseInt(step_size.split("s")[0]);
            }
            if (step_size.endsWith("m")) {
                dateType = Calendar.MINUTE;
                num = Integer.parseInt(step_size.split("m")[0]);
            }
            if (step_size.endsWith("h")) {
                dateType = Calendar.HOUR;
                num = Integer.parseInt(step_size.split("h")[0]);
            }
            if (step_size.endsWith("d")) {
                dateType = Calendar.DAY_OF_MONTH;
                num = Integer.parseInt(step_size.split("d")[0]);
            }

            //finish成功状态 判断last_time 是否超过结束日期,超过，删除任务,更新状态
            if (DateUtil.add(last, dateType, num).after(quartzJobInfo.getEnd_time())) {
                logger.info("[" + jobType + "] JOB ,当前任务时间超过结束时间,任务结束");
                insertLog(quartzJobInfo, "info", "[" + jobType + "] JOB ,当前任务时间超过结束时间,任务结束");
                quartzJobInfo.setStatus("finish");
                //删除quartz 任务
                quartzManager2.deleteTask(quartzJobInfo, "finish");

                return false;
            }

            if (quartzJobInfo.getStart_time().before(DateUtil.add(last, dateType, num)) ||
                    quartzJobInfo.getStart_time().equals(DateUtil.add(last, dateType, num))) {
                logger.info("[" + jobType + "] JOB,上次执行任务成功,计数新的执行日期:" + DateUtil.add(last, dateType, num));
                insertLog(quartzJobInfo, "info", "[" + jobType + "] JOB,上次执行任务成功,计数新的执行日期:" + DateUtil.add(last, dateType, num));
                quartzJobInfo.setLast_time(DateUtil.add(last, dateType, num));
                quartzJobInfo.setNext_time(DateUtil.add(quartzJobInfo.getLast_time(), dateType, num));
            } else {
                logger.info("[" + jobType + "] JOB,首次执行任务,下次执行日期为起始日期:" + quartzJobInfo.getStart_time());
                insertLog(quartzJobInfo, "info", "[" + jobType + "] JOB,首次执行任务,下次执行日期为起始日期:" + quartzJobInfo.getStart_time());
            }
        }

        //error 状态,retry 状态  last_time 不变继续执行
        if (quartzJobInfo.getLast_status() != null && (quartzJobInfo.getLast_status().equals("dispatch") || quartzJobInfo.getLast_status().equals("error") || quartzJobInfo.getLast_status().equals("retry"))) {
            logger.info("[" + jobType + "] JOB ,上次任务处理失败,将重新执行,上次日期:" + quartzJobInfo.getLast_time());
            //插入日志
            insertLog(quartzJobInfo, "info", "[" + jobType + "] JOB ,上次任务处理失败,将重新执行,上次日期:" + quartzJobInfo.getLast_time());
        }

        return true;

    }


    /**
     * 时间序列发送etl任务到后台执行
     *
     * @param jobType
     * @param task_logs_id
     * @param exe_status
     * @param quartzJobInfo
     * @param taskLogsMapper
     * @param quartzManager2
     * @return
     */
    public static boolean runTimeSeq(String jobType, String task_logs_id, boolean exe_status, QuartzJobInfo quartzJobInfo, TaskLogsMapper taskLogsMapper, QuartzManager2 quartzManager2) {
        logger.info("[" + jobType + "] JOB ,调度命令执行成功,准备发往任务到后台ETL执行");
        insertLog(quartzJobInfo, "info", "[" + jobType + "] JOB ,调度命令执行成功,准备发往任务到后台ETL执行");
        exe_status = sendZdh(task_logs_id, "[" + jobType + "]", exe_status, quartzJobInfo);

        if (exe_status) {
            logger.info("[" + jobType + "] JOB ,执行命令成功");
            insertLog(quartzJobInfo, "info", "[" + jobType + "] JOB ,执行命令成功");

            if (quartzJobInfo.getEnd_time() == null) {
                logger.info("[" + jobType + "] JOB ,结束日期为空设置当前日期为结束日期");
                insertLog(quartzJobInfo, "info", "[" + jobType + "] JOB ,结束日期为空设置当前日期为结束日期");
                quartzJobInfo.setEnd_time(new Timestamp(new Date().getTime()));
            }

        } else {
            setJobLastStatus(quartzJobInfo, task_logs_id, taskLogsMapper);
        }
        return exe_status;
    }

    /**
     * 执行一次任务发送etl任务到后台执行
     *
     * @param jobType
     * @param task_logs_id
     * @param exe_status
     * @param quartzJobInfo
     * @param taskLogsMapper
     * @param quartzManager2
     * @return
     */
    public static boolean runOnce(String jobType, String task_logs_id, boolean exe_status, QuartzJobInfo quartzJobInfo, TaskLogsMapper taskLogsMapper, QuartzManager2 quartzManager2) {
        logger.info("[" + jobType + "] JOB ,调度命令执行成功,准备发往任务到后台ETL执行");
        insertLog(quartzJobInfo, "info", "[" + jobType + "] JOB ,调度命令执行成功,准备发往任务到后台ETL执行");
        exe_status = sendZdh(task_logs_id, "[" + jobType + "]", exe_status, quartzJobInfo);

        if (exe_status) {
            logger.info("[" + jobType + "] JOB ,执行命令成功");
            insertLog(quartzJobInfo, "info", "[" + jobType + "] JOB ,执行命令成功");

            if (quartzJobInfo.getEnd_time() == null) {
                logger.info("[" + jobType + "] JOB ,结束日期为空设置当前日期为结束日期");
                insertLog(quartzJobInfo, "info", "[" + jobType + "] JOB ,结束日期为空设置当前日期为结束日期");
                quartzJobInfo.setEnd_time(new Timestamp(new Date().getTime()));
            }

            System.out.println("===================================");
            quartzJobInfo.setStatus("finish");
            //delete 里面包含更新
            quartzManager2.deleteTask(quartzJobInfo, "finish");
            //插入日志
            logger.info("[" + jobType + "] JOB ,结束调度任务");
            insertLog(quartzJobInfo, "info", "[" + jobType + "] JOB ,结束调度任务");

        } else {
            setJobLastStatus(quartzJobInfo, task_logs_id, taskLogsMapper);
            //如果执行失败 next_time 时间不变,last_time 不变
        }
        return exe_status;
    }

    /**
     * 重复执行任务发送etl任务到后台执行
     *
     * @param jobType
     * @param task_logs_id
     * @param exe_status
     * @param quartzJobInfo
     * @param taskLogsMapper
     * @param quartzManager2
     * @return
     */
    public static boolean runRepeat(String jobType, String task_logs_id, boolean exe_status, QuartzJobInfo quartzJobInfo, TaskLogsMapper taskLogsMapper, QuartzManager2 quartzManager2) {
        logger.info("[" + jobType + "] JOB ,调度命令执行成功,准备发往任务到后台ETL执行");
        insertLog(quartzJobInfo, "info", "[" + jobType + "] JOB ,调度命令执行成功,准备发往任务到后台ETL执行");
        exe_status = sendZdh(task_logs_id, "[" + jobType + "]", exe_status, quartzJobInfo);
        if (exe_status) {
            logger.info("[" + jobType + "] JOB ,执行命令成功");
            insertLog(quartzJobInfo, "INFO", "[" + jobType + "] JOB ,执行命令成功");

            if (quartzJobInfo.getEnd_time() == null) {
                logger.info("[" + jobType + "] JOB ,结束日期为空设置当前日期为结束日期");
                insertLog(quartzJobInfo, "INFO", "[" + jobType + "] JOB ,结束日期为空设置当前日期为结束日期");
                quartzJobInfo.setEnd_time(new Timestamp(new Date().getTime()));
            }
        } else {
            setJobLastStatus(quartzJobInfo, task_logs_id, taskLogsMapper);
        }
        return exe_status;
    }

    /**
     * 调度执行的任务命令,jdbc,失败后触发此方法
     * @param jobType
     * @param task_logs_id
     * @param quartzJobInfo
     * @param taskLogsMapper
     */
    public static void jobFail(String jobType, String task_logs_id, QuartzJobInfo quartzJobInfo, TaskLogsMapper taskLogsMapper) {
        QuartzManager2 quartzManager2=(QuartzManager2)SpringContext.getBean("quartzManager2");
        logger.info("[" + jobType + "] JOB ,调度命令执行失败未能发往任务到后台ETL执行");
        insertLog(quartzJobInfo, "info", "[" + jobType + "] JOB ,调度命令执行失败未能发往任务到后台ETL执行");
        String msg = "[" + jobType + "] JOB ,调度命令执行失败未能发往任务到后台ETL执行,重试次数已达到最大,状态设置为error";
        String status = "error";
        if (quartzJobInfo.getPlan_count().equalsIgnoreCase("-1") || Long.parseLong(quartzJobInfo.getPlan_count()) > quartzJobInfo.getCount()) {
            //重试
            status = "wait_retry";
            msg = "[" + jobType + "] JOB ,调度命令执行失败未能发往任务到后台ETL执行,状态设置为wait_retry等待重试";
            if (quartzJobInfo.getPlan_count().equalsIgnoreCase("-1")) {
                msg = msg + ",并检测到重试次数为无限次";
            }
        }
        logger.info(msg);
        insertLog(quartzJobInfo, "ERROR", msg);
        int interval_time = (quartzJobInfo.getInterval_time() == null || quartzJobInfo.getInterval_time().equals("")) ? 5 : Integer.parseInt(quartzJobInfo.getInterval_time());
        //调度时异常
        updateTaskLogError(task_logs_id, "9", taskLogsMapper, status, interval_time);
        quartzJobInfo.setLast_status(status);
        if(status.equalsIgnoreCase("error")){
            quartzManager2.deleteTask(quartzJobInfo, "finish");
        }
    }

    public static void setJobLastStatus(QuartzJobInfo quartzJobInfo, String task_logs_id, TaskLogsMapper taskLogsMapper) {
        QuartzManager2 quartzManager2=(QuartzManager2)SpringContext.getBean("quartzManager2");
        String status = "error";
        String msg = "发送ETL任务到zdh处理引擎,存在问题,重试次数已达到最大,状态设置为error";
        if (quartzJobInfo.getPlan_count().equalsIgnoreCase("-1") || Long.parseLong(quartzJobInfo.getPlan_count()) > quartzJobInfo.getCount()) {
            //重试
            status = "wait_retry";
            msg = "发送ETL任务到zdh处理引擎,存在问题,状态设置为wait_retry等待重试";
            if (quartzJobInfo.getPlan_count().equalsIgnoreCase("-1")) {
                msg = msg + ",并检测到重试次数为无限次";
            }
        }
        logger.info(msg);
        insertLog(quartzJobInfo, "ERROR", msg);
        int interval_time = (quartzJobInfo.getInterval_time() == null || quartzJobInfo.getInterval_time().equals("")) ? 5 : Integer.parseInt(quartzJobInfo.getInterval_time());
        updateTaskLogError(task_logs_id, "17", taskLogsMapper, status, interval_time);
        quartzJobInfo.setLast_status(status);
        if(status.equalsIgnoreCase("error")){
            quartzManager2.deleteTask(quartzJobInfo, "finish");
        }
    }

    public static User getUser() {
        User user = (User) SecurityUtils.getSubject().getPrincipal();
        return user;
    }

    /**
     * 选择具体的job执行引擎
     *
     * @param quartzJobInfo
     */
    public static void chooseJobBean(QuartzJobInfo quartzJobInfo, Boolean is_retry) {
        if (quartzJobInfo.getJob_type().equals("SHELL")) {
            logger.info("调度任务[SHELL],开始调度");
            ShellJob.run(quartzJobInfo, is_retry);
        } else if (quartzJobInfo.getJob_type().equals("JDBC")) {
            logger.info("调度任务[JDBC],开始调度");
            JdbcJob.run(quartzJobInfo, is_retry);
        } else if (quartzJobInfo.getJob_type().equals("FTP")) {
            logger.info("调度任务[FTP],开始调度");
            FtpJob.run(quartzJobInfo);
        } else if (quartzJobInfo.getJob_type().equals("HDFS")) {
            logger.info("调度任务[HDFS],开始调度");
            HdfsJob.run(quartzJobInfo, is_retry);
        } else if (quartzJobInfo.getJob_type().equals("EMAIL")) {
            logger.debug("调度任务[EMAIL],开始调度");
            EmailJob.run(quartzJobInfo);
            EmailJob.notice_event();
        } else if (quartzJobInfo.getJob_type().equals("RETRY")) {
            logger.debug("调度任务[RETRY],开始调度");
            RetryJob.run(quartzJobInfo);
        } else {
            ZdhLogsService zdhLogsService = (ZdhLogsService) SpringContext.getBean("zdhLogsServiceImpl");
            quartzJobInfo.setTask_log_id("system");
            JobCommon.insertLog(quartzJobInfo, "ERROR",
                    "无法找到对应的任务类型,请检查调度任务配置中的任务类型");
            logger.info("无法找到对应的任务类型,请检查调度任务配置中的任务类型");
        }
    }

}
