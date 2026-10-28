package com.bankos.controller;

import com.bankos.agent.AgentOrchestrator;
import com.bankos.dto.BatchEvent;
import com.bankos.dto.BatchResponse;
import com.bankos.model.Batch;
import com.bankos.model.BatchStatus;
import com.bankos.service.BatchService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService batchService;
    private final AgentOrchestrator orchestrator;

    public BatchController(BatchService batchService, AgentOrchestrator orchestrator) {
        this.batchService = batchService;
        this.orchestrator = orchestrator;
    }

    /** Upload payment/settlement CSVs and invoice PDFs. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BatchResponse> upload(@RequestPart("payments") MultipartFile payments,
                                                @RequestPart("settlements") MultipartFile settlements,
                                                @RequestPart(value = "invoices", required = false) List<MultipartFile> invoices) {
        Batch batch = batchService.createBatch(payments, settlements, invoices);
        return ResponseEntity.status(HttpStatus.CREATED).body(batchService.toResponse(batch));
    }

    @GetMapping
    public List<BatchResponse> list() {
        return batchService.listBatches();
    }

    /** Start the batch pipeline. 202 while running in the background, 200 once finished. */
    @PostMapping("/{id}/process")
    public ResponseEntity<BatchResponse> process(@PathVariable String id,
                                                 @RequestParam(required = false) Integer policyVersion) {
        Batch batch = orchestrator.start(id, policyVersion);
        HttpStatus status = batch.getStatus() == BatchStatus.READY_FOR_REVIEW || batch.getStatus() == BatchStatus.FAILED
                ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(batchService.toResponse(batch));
    }

    /** Read processing status and totals. */
    @GetMapping("/{id}")
    public BatchResponse get(@PathVariable String id) {
        return batchService.toResponse(batchService.getBatch(id));
    }

    /** Read agent stage and tool execution events. */
    @GetMapping("/{id}/events")
    public List<BatchEvent> events(@PathVariable String id) {
        return batchService.events(id);
    }
}
