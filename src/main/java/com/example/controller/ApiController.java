package com.example.controller;

import com.example.service.InMemoryCareerService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping({"/api", ""})
public class ApiController {
  private final InMemoryCareerService service;

  public ApiController(InMemoryCareerService service) {
    this.service = service;
  }

  @GetMapping("/auth/captcha")
  public Map<String, Object> getCaptcha() {
    return service.issueCaptcha();
  }

  @PostMapping("/auth/login")
  public Map<String, Object> login(@RequestBody Map<String, Object> payload) {
    return service.login(
      stringValue(payload.get("email")),
      stringValue(payload.get("password")),
      stringValue(payload.get("captchaToken")),
      stringValue(payload.get("captchaAnswer"))
    );
  }

  @PostMapping("/auth/register")
  public Map<String, Object> register(@RequestBody Map<String, Object> payload) {
    return service.register(
      stringValue(payload.get("name")),
      stringValue(payload.get("email")),
      stringValue(payload.get("password")),
      stringValue(payload.get("role")),
      stringValue(payload.get("captchaToken")),
      stringValue(payload.get("captchaAnswer"))
    );
  }

  @GetMapping("/auth/me")
  public Map<String, Object> me(@RequestHeader("Authorization") String authorization) {
    return service.getCurrentUser(authorization);
  }

  @GetMapping("/users/me")
  public Map<String, Object> getProfile(@RequestHeader("Authorization") String authorization) {
    return service.getCurrentUser(authorization);
  }

  @PutMapping("/users/me")
  public Map<String, Object> updateProfile(@RequestHeader("Authorization") String authorization, @RequestBody Map<String, Object> payload) {
    return service.updateCurrentUser(authorization, payload);
  }

  @GetMapping("/officer/users")
  public List<Map<String, Object>> getAllUsers(@RequestHeader("Authorization") String authorization) {
    return service.getAllUsers(authorization);
  }

  @PatchMapping("/officer/users/{userId}/role")
  public Map<String, Object> updateUserRole(@RequestHeader("Authorization") String authorization,
                                            @PathVariable long userId,
                                            @RequestBody Map<String, Object> payload) {
    return service.updateUserRole(authorization, userId, stringValue(payload.get("role")));
  }

  @DeleteMapping("/officer/users/{userId}")
  public void deleteUser(@RequestHeader("Authorization") String authorization, @PathVariable long userId) {
    service.deleteUser(authorization, userId);
  }

  @GetMapping("/jobs")
  public List<Map<String, Object>> getJobs(@RequestHeader(value = "Authorization", required = false) String authorization) {
    return service.getJobs(authorization);
  }

  @GetMapping("/jobs/matched")
  public List<Map<String, Object>> getMatchedJobs(@RequestHeader("Authorization") String authorization) {
    return service.getMatchedJobs(authorization);
  }

  @PostMapping("/jobs")
  public Map<String, Object> createJob(@RequestHeader("Authorization") String authorization, @RequestBody Map<String, Object> payload) {
    return service.createJob(authorization, payload);
  }

  @PutMapping("/jobs/{jobId}")
  public Map<String, Object> updateJob(@RequestHeader("Authorization") String authorization,
                                       @PathVariable long jobId,
                                       @RequestBody Map<String, Object> payload) {
    return service.updateJob(authorization, jobId, payload);
  }

  @DeleteMapping("/jobs/{jobId}")
  public void deleteJob(@RequestHeader("Authorization") String authorization, @PathVariable long jobId) {
    service.deleteJob(authorization, jobId);
  }

  @GetMapping("/applications")
  public List<Map<String, Object>> getApplications(@RequestHeader(value = "Authorization", required = false) String authorization) {
    return service.getApplications(authorization);
  }

  @GetMapping("/applications/me")
  public List<Map<String, Object>> getStudentApplications(@RequestHeader("Authorization") String authorization) {
    return service.getStudentApplications(authorization);
  }

  @GetMapping("/applications/employer")
  public List<Map<String, Object>> getEmployerApplications(@RequestHeader("Authorization") String authorization) {
    return service.getEmployerApplications(authorization);
  }

  @PostMapping("/applications")
  public Map<String, Object> applyForJob(@RequestHeader("Authorization") String authorization,
                                         @RequestBody Map<String, Object> payload) {
    return service.apply(authorization, longValue(payload.get("jobId")), null);
  }

  @PatchMapping("/applications/{applicationId}/status")
  public Map<String, Object> updateApplicationStatus(@RequestHeader("Authorization") String authorization,
                                                     @PathVariable long applicationId,
                                                     @RequestBody Map<String, Object> payload) {
    return service.updateApplicationStatus(authorization, applicationId, stringValue(payload.get("status")));
  }

  @DeleteMapping("/applications/{applicationId}")
  public void deleteApplication(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @PathVariable long applicationId) {
    service.deleteApplication(authorization, applicationId);
  }

  @PostMapping("/apply")
  public Map<String, Object> legacyApply(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody Map<String, Object> payload) {
    return service.apply(authorization, longValue(payload.get("jobId")), longValue(payload.get("studentId")));
  }

  @DeleteMapping("/apply/{applicationId}")
  public void legacyDeleteApplication(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable long applicationId) {
    service.deleteApplication(authorization, applicationId);
  }

  @GetMapping("/resumes/me")
  public Map<String, Object> getResume(@RequestHeader("Authorization") String authorization) {
    return service.getResume(authorization);
  }

  @PostMapping(value = "/resumes/me", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String, Object> uploadResume(@RequestHeader("Authorization") String authorization,
                                          @RequestParam("file") MultipartFile file) {
    return service.uploadResume(authorization, file.getOriginalFilename(), file.getContentType());
  }

  @DeleteMapping("/resumes/me")
  public void deleteResume(@RequestHeader("Authorization") String authorization) {
    service.deleteResume(authorization);
  }

  @PostMapping(value = "/upload/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String, Object> legacyUploadResume(@RequestHeader("Authorization") String authorization,
                                                @PathVariable long id,
                                                @RequestParam("file") MultipartFile file) {
    return service.uploadResume(authorization, file.getOriginalFilename(), file.getContentType());
  }

  @GetMapping("/officer/reports/summary")
  public Map<String, Object> getSummary(@RequestHeader("Authorization") String authorization) {
    return service.getSummary(authorization);
  }

  @GetMapping("/officer/reports/placements")
  public List<Map<String, Object>> getPlacements(@RequestHeader("Authorization") String authorization) {
    return service.getPlacements(authorization);
  }

  @GetMapping("/officer/interactions")
  public List<Map<String, Object>> getInteractions(@RequestHeader("Authorization") String authorization) {
    return service.getInteractions(authorization);
  }

  @GetMapping("/students/me/meetings")
  public List<Map<String, Object>> getStudentMeetings(@RequestHeader("Authorization") String authorization) {
    return service.getStudentMeetings(authorization);
  }

  @PostMapping("/students/me/meetings/{interactionId}/join-request")
  public Map<String, Object> requestMeetingJoin(@RequestHeader("Authorization") String authorization,
                                                @PathVariable long interactionId) {
    return service.requestMeetingJoin(authorization, interactionId);
  }

  @PostMapping("/officer/interactions")
  public Map<String, Object> createInteraction(@RequestHeader("Authorization") String authorization,
                                               @RequestBody Map<String, Object> payload) {
    return service.createInteraction(authorization, payload);
  }

  @DeleteMapping("/officer/interactions/{interactionId}")
  public void deleteInteraction(@RequestHeader("Authorization") String authorization,
                                @PathVariable long interactionId) {
    service.deleteInteraction(authorization, interactionId);
  }

  @PatchMapping("/officer/interactions/{interactionId}/participants/{studentId}/admit")
  public Map<String, Object> admitMeetingParticipant(@RequestHeader("Authorization") String authorization,
                                                     @PathVariable long interactionId,
                                                     @PathVariable long studentId) {
    return service.admitMeetingParticipant(authorization, interactionId, studentId);
  }

  @GetMapping("/officer/overview")
  public Map<String, Object> getAdminOverview(@RequestHeader("Authorization") String authorization) {
    return service.getAdminOverview(authorization);
  }

  private static String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static Long longValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    return Long.parseLong(String.valueOf(value));
  }
}
