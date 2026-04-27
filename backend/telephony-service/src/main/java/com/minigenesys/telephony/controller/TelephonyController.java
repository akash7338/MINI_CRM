package com.minigenesys.telephony.controller;

import com.minigenesys.telephony.service.TelephonyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/telephony")
@RequiredArgsConstructor
public class TelephonyController {

    private final TelephonyService telephonyService;

    @PostMapping(value = "/twilio/inbound", produces = MediaType.APPLICATION_XML_VALUE)
    public String handleInbound(
            @RequestParam("CallSid") String callSid,
            @RequestParam("From") String from,
            @RequestParam("To") String to) {
        
        telephonyService.handleInboundCall(callSid, from, to);

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<Response>\n" +
               "    <Say>Please wait while we connect you to an agent</Say>\n" +
               "    <Pause length=\"1\"/>\n" +
               "    <Redirect>/api/v1/telephony/twilio/wait?callSid=" + callSid + "</Redirect>\n" +
               "</Response>";
    }

    @PostMapping("/twilio/status")
    public ResponseEntity<Void> handleStatus(
            @RequestParam("CallSid") String callSid,
            @RequestParam("CallStatus") String callStatus,
            @RequestParam("From") String from,
            @RequestParam("To") String to) {
        
        telephonyService.handleStatusCallback(callSid, callStatus, from, to);
        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/twilio/wait", produces = MediaType.APPLICATION_XML_VALUE)
    public String handleWait(@RequestParam("callSid") String callSid) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<Response>\n" +
               "    <Say>Your call is still in queue</Say>\n" +
               "    <Pause length=\"5\"/>\n" +
               "    <Redirect>/api/v1/telephony/twilio/wait?callSid=" + callSid + "</Redirect>\n" +
               "</Response>";
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
