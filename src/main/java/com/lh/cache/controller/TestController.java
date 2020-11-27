package com.lh.cache.controller;

import com.lh.cache.dto.Message;
import com.lh.cache.service.TestCache;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author haol
 * @Date 20-11-20 10:33
 * @Version 1.0
 * @Desciption
 */
@RestController
@RequestMapping("/")
public class TestController {

    private final TestCache testCache;

    public TestController(TestCache testCache) {
        this.testCache = testCache;
    }

    @ResponseBody
    @RequestMapping("")
    public Object test(){
        return "{}";
    }

    @ResponseBody
    @RequestMapping("/add")
    public Object add(Message message){
        return testCache.add(message);
    }

    @ResponseBody
    @RequestMapping("/get")
    public Object get(@RequestParam("id") int id){
        return testCache.get(id);
    }

    @ResponseBody
    @RequestMapping("/update")
    public Object update(Message message){
        return testCache.update(message);
    }

    @ResponseBody
    @RequestMapping("/delete")
    public Object delete(@RequestParam("id") int id){
        return testCache.delete(id);
    }
}
