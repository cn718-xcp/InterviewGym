package com.interviewgym.api;

import com.interviewgym.rag.ResumeIndexService;
import com.interviewgym.rag.ResumeStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 简历管理端点（spec: resume-rag.md）。单活跃简历：PUT 全量替换索引，GET 查状态，DELETE 清除。
 * 不记录简历内容日志（红线 2）。
 */
@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    private final ResumeIndexService resumeIndexService;

    public ResumeController(ResumeIndexService resumeIndexService) {
        this.resumeIndexService = resumeIndexService;
    }

    /** PUT /api/resume {text} → 切块 + 嵌入 + 全量重建索引（1 次嵌入调用） */
    @PutMapping
    public ResumeStatus save(@RequestBody ResumeUploadRequest request) {
        return resumeIndexService.save(request.text());
    }

    /** GET /api/resume → 当前简历状态（未上传 → 404） */
    @GetMapping
    public ResponseEntity<ResumeStatus> status() {
        return resumeIndexService.status()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** DELETE /api/resume → 清除简历及索引（幂等） */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete() {
        resumeIndexService.delete();
    }

    record ResumeUploadRequest(String text) {
    }
}
