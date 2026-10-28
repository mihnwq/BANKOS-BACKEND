package com.bankos.controller;

import com.bankos.dto.PolicyReplayRequest;
import com.bankos.dto.PolicyReplayResult;
import com.bankos.dto.PolicyResponse;
import com.bankos.service.PolicySimulationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/policies")
public class PolicyController {

    private final PolicySimulationService policySimulationService;

    public PolicyController(PolicySimulationService policySimulationService) {
        this.policySimulationService = policySimulationService;
    }

    @GetMapping
    public List<PolicyResponse> list() {
        return policySimulationService.list();
    }

    @GetMapping("/{version}")
    public PolicyResponse get(@PathVariable Integer version) {
        return PolicyResponse.from(policySimulationService.get(version));
    }

    /** Compare case decisions under proposed rules (creates a new version; history is untouched). */
    @PostMapping("/replay")
    public PolicyReplayResult replay(@Valid @RequestBody PolicyReplayRequest request) {
        return policySimulationService.replay(request);
    }
}
