// Copyright (c) Microsoft Corporation.
// Licensed under the MIT License.
package com.microsoft.hydralab.agent.service;

import com.alibaba.fastjson.JSONObject;
import com.microsoft.hydralab.common.util.Const;
import com.microsoft.hydralab.common.entity.agent.MobileDevice;
import com.microsoft.hydralab.common.entity.center.AgentUser;
import com.microsoft.hydralab.common.entity.center.TestTaskSpec;
import com.microsoft.hydralab.common.entity.common.*;
import com.microsoft.hydralab.agent.runner.TestRunningCallback;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import javax.annotation.Resource;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

@Service("WebSocketClient")
@Slf4j
public class AgentWebSocketClientService implements TestRunningCallback {
    @Value("${app.registry.agent-type}")
    public int agentTypeValue;
    @Value("${app.registry.name}")
    String agentName;
    @Value("${app.registry.id}")
    String agentId;
    @Value("${app.registry.secret}")
    String agentSecret;
    @Resource
    DeviceControlService deviceControlService;
    @Resource
    AgentUpdateService agentUpdateService;
    AgentUser agentUser;
    @Value("${agent.version}")
    private String version;
    private SendMessageCallback sendMessageCallback;

    public void onMessage(Message message) {
        log.info("onMessage Receive bytes message {}", message);
        String path = message.getPath();
        Message response = null;
        switch (path) {
            case Const.Path.AUTH:
                provideAuthInfo(message);
                return;
            case Const.Path.DEVICE_UPDATE:
                if (!(message.getBody() instanceof JSONObject)) {
                    break;
                }
                JSONObject deviceData = (JSONObject) message.getBody();
                DeviceInfo device = deviceControlService.updateDeviceScope(deviceData.getString(Const.AgentConfig.serial_param), deviceData.getBoolean(Const.AgentConfig.scope_param));
                response = new Message();
                response.setPath(message.getPath());
                response.setSessionId(message.getSessionId());
                response.setBody(device);
                log.info("/api/device/update device SN: {}", device.getSerialNum());
                break;
            case Const.Path.AGENT_UPDATE:
                if (!(message.getBody() instanceof AgentUpdateTask)) {
                    break;
                }
                AgentUpdateTask taskInfo = (AgentUpdateTask) message.getBody();
                agentUpdateService.updateAgentPackage(taskInfo);
                break;
            case Const.Path.DEVICE_LIST:
                Set<DeviceInfo> allConnectedDevice = deviceControlService.getAllConnectedDevice();
                deviceControlService.captureAllScreensSync();
                ArrayList<DeviceInfo> deviceInfos = new ArrayList<>(allConnectedDevice);
                deviceInfos.sort(Comparator.comparing(d -> d.getName() + d.getSerialNum()));
                response = new Message();
                response.setPath(message.getPath());
                response.setSessionId(message.getSessionId());
                response.setBody(deviceInfos);
                log.info("/api/device/list device SN: {}", deviceInfos.stream().map(MobileDevice::getSerialNum).collect(Collectors.joining(",")));
                break;
            case Const.Path.TEST_TASK_CANCEL:
                if (!(message.getBody() instanceof JSONObject)) {
                    break;
                }
                JSONObject data = (JSONObject) message.getBody();
                deviceControlService.cancelTestTaskById(data.getString(Const.AgentConfig.task_id_param));
                break;
            case Const.Path.TEST_TASK_RUN:
                try {
                    if (!(message.getBody() instanceof TestTaskSpec)) {
                        break;
                    }
                    TestTaskSpec testTaskSpec = (TestTaskSpec) message.getBody();
                    if (testTaskSpec.instrumentationArgs != null) {
                        log.info("instrumentationArgs: {}", testTaskSpec.instrumentationArgs);
                    }
                    log.info("RunTestTask:, testSpec {}", testTaskSpec);

                    if (testTaskSpec.runningType == null || "".equals(testTaskSpec.runningType)) {
                        testTaskSpec.runningType = TestTask.TestRunningType.INSTRUMENTATION;
                    }
                    TestTask testTask = deviceControlService.runTestTask(testTaskSpec);
                    if (testTask == null) {
                        response = Message.error(message, 404, "No device meet the requirement");
                    } else {
                        response = Message.response(message, testTask);
                        response.setPath(Const.Path.TEST_TASK_UPDATE);
                    }
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                    response = Message.error(message, 500, e.getMessage() + e.getClass().getName());
                }
                break;
        }
        if (response == null) {
            return;
        }
        send(response);
    }


    private void provideAuthInfo(Message message) {
        Message responseAuth = new Message();
        responseAuth.setSessionId(message.getSessionId());

        if (agentUser == null) {
            agentUser = new AgentUser();
        }
        agentUser.setId(agentId);
        agentUser.setName(agentName);
        agentUser.setSecret(agentSecret);
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            agentUser.setHostname(localHost.getHostName());
            agentUser.setIp(localHost.getHostAddress());
        } catch (UnknownHostException ignore) {
        }
        agentUser.setOs(System.getProperties().getProperty("os.name"));
        agentUser.setVersion(version);
        agentUser.setDeviceType(agentTypeValue);
        responseAuth.setBody(agentUser);
        responseAuth.setPath(message.getPath());
        send(responseAuth);
    }

    public void send(Message message) {
        Assert.notNull(sendMessageCallback, "no send callback set");
        sendMessageCallback.send(message);
    }

    public void setSendMessageCallback(SendMessageCallback sendMessageCallback) {
        this.sendMessageCallback = sendMessageCallback;
    }

    @Override
    public void onAllComplete(TestTask testTask) {
        log.info("test task {} onAllComplete in webclient, send message", testTask.getId());
        send(Message.ok(Const.Path.TEST_TASK_UPDATE, testTask));
    }

    @Override
    public void onOneDeviceComplete(TestTask testTask, DeviceInfo deviceControl, Logger logger, DeviceTestTask result) {

    }

    @Override
    public void onDeviceOffline(TestTask testTask) {
        log.info("test task {} re-queue, send message", testTask.getId());
        send(Message.ok(Const.Path.TEST_TASK_RETRY, testTask));
    }

    public String getAgentName(){
        return agentName;
    }

    public interface SendMessageCallback {
        void send(Message message);
    }
}