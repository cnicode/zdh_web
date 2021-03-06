package com.zyc.zdh.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.zyc.zdh.config.DateConverter;
import com.zyc.zdh.dao.ZdhNginxMapper;
import com.zyc.zdh.entity.NoticeInfo;
import com.zyc.zdh.entity.User;
import com.zyc.zdh.entity.ZdhDownloadInfo;
import com.zyc.zdh.entity.ZdhNginx;
import com.zyc.zdh.shiro.RedisUtil;
import org.apache.shiro.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

import java.beans.PropertyEditorSupport;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
@Controller
public class SystemController extends BaseController{

    @Autowired
    ZdhNginxMapper zdhNginxMapper;
    @Autowired
    RedisUtil redisUtil;


    @RequestMapping(value = "/{url}", method = RequestMethod.GET)
    public String dynApiDemo2(@PathVariable("url") String url) {

        return "etl/" + url;
    }

    @RequestMapping(value = "/file_manager", method = RequestMethod.GET)
    public String file_manager() {

        return "file_manager";
    }

    @RequestMapping("/getFileManager")
    @ResponseBody
    public ZdhNginx getFileManager() {
        ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(getUser().getId());
        return zdhNginx;
    }

    @RequestMapping(value = "/file_manager_up", method = RequestMethod.POST)
    @ResponseBody
    public String file_manager_up(ZdhNginx zdhNginx) {

        ZdhNginx zdhNginx1 = zdhNginxMapper.selectByOwner(getUser().getId());
        zdhNginx.setOwner(getUser().getId());
        if (zdhNginx.getPort().equals("")) {
            zdhNginx.setPort("22");
        }
        if (zdhNginx1 != null) {
            zdhNginx.setId(zdhNginx1.getId());
            zdhNginxMapper.updateByPrimaryKey(zdhNginx);
        } else {
            zdhNginxMapper.insert(zdhNginx);
        }

        JSONObject json = new JSONObject();
        json.put("success", "200");
        return json.toJSONString();
    }

    @RequestMapping(value = "/notice_list", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public String notice() {
        //System.out.println("加载缓存中通知事件");
        List<NoticeInfo> noticeInfos = new ArrayList<>();
        if (!redisUtil.exists("zdhdownloadinfos_" + getUser().getId())) {
            return JSON.toJSONString(noticeInfos);
        }
        String json = redisUtil.get("zdhdownloadinfos_" + getUser().getId()).toString();
        if (json != null && !json.equals("")) {
            List<ZdhDownloadInfo> cache = JSON.parseArray(json, ZdhDownloadInfo.class);
            Iterator<ZdhDownloadInfo> iterator = cache.iterator();
            while (iterator.hasNext()) {
                ZdhDownloadInfo zdhDownloadInfo = iterator.next();
                if (zdhDownloadInfo.getOwner().equals(getUser().getId())) {
                    NoticeInfo noticeInfo = new NoticeInfo();
                    noticeInfo.setMsg_type("文件下载");
                    int last_index = zdhDownloadInfo.getFile_name().lastIndexOf("/");
                    noticeInfo.setMsg_title(zdhDownloadInfo.getFile_name().substring(last_index + 1) + "完成下载");
                    noticeInfo.setMsg_url("download_index");
                    noticeInfos.add(noticeInfo);
                }
            }
        }

        return JSON.toJSONString(noticeInfos);
    }

    /**
     * 使用帮助
     * @return
     */
    @RequestMapping("/readme")
    public String read_me() {

        return "read_me";
    }


    public User getUser() {
        User user = (User) SecurityUtils.getSubject().getPrincipal();
        return user;
    }


}
