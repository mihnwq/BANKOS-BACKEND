package com.bankos.controller;

import com.bankos.agent.AgentOrchestrator;
import com.bankos.dto.CaseResponse;
import com.bankos.dto.CaseSummary;
import com.bankos.dto.ReviewRequest;
import com.bankos.dto.ReviewResponse;
import com.bankos.model.CaseStatus;
import com.bankos.model.CaseType;
import com.bankos.service.CaseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private final CaseService caseService;
    private final AgentOrchestrator orchestrator;

    public CaseController(CaseService caseService, AgentOrchestrator orchestrator) {
        this.caseService = caseService;
        this.orchestrator = orchestrator;
    }

    /** List case summaries, optionally filtered by batch, status and type. */
    @GetMapping
    public List<CaseSummary> list(@RequestParam(required = false) String batchId,
                                  @RequestParam(required = false) CaseStatus status,
                                  @RequestParam(required = false) CaseType type) {
        return caseService.list(batchId, status, type);
    }

    /** Evidence-backed case details, agent trace and audit history. */
    @GetMapping("/{id}")
    public CaseResponse get(@PathVariable String id) {
        return caseService.detail(id);
    }

    /** Run or rerun the AI investigation for one case. */
    @PostMapping("/{id}/investigate")
    public CaseResponse investigate(@PathVariable String id) {
        return caseService.toResponse(orchestrator.reinvestigate(id));
    }

    /** Record an authorized human action (append-only audit event). */
    @PostMapping("/{id}/review")
    public ResponseEntity<ReviewResponse> review(@PathVariable String id, @Valid @RequestBody ReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(caseService.review(id, request));
    }
}
