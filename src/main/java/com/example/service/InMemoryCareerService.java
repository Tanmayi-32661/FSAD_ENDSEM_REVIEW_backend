package com.example.service;

import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class InMemoryCareerService {
  private static final long SESSION_DURATION_MS = 24L * 60L * 60L * 1000L;
  private static final long CAPTCHA_DURATION_MS = 5L * 60L * 1000L;
  private static final List<String> FALLBACK_RESUME_SKILLS = List.of("java", "spring boot", "mysql", "react");
  private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

  private final AtomicLong userIds = new AtomicLong();
  private final AtomicLong jobIds = new AtomicLong();
  private final AtomicLong applicationIds = new AtomicLong();
  private final AtomicLong resumeIds = new AtomicLong();
  private final AtomicLong interactionIds = new AtomicLong();

  private final Map<Long, UserAccount> users = new ConcurrentHashMap<>();
  private final Map<Long, JobPosting> jobs = new ConcurrentHashMap<>();
  private final Map<Long, PlacementApplication> applications = new ConcurrentHashMap<>();
  private final Map<Long, ResumeRecord> resumes = new ConcurrentHashMap<>();
  private final Map<Long, OfficerInteraction> interactions = new ConcurrentHashMap<>();
  private final Map<String, SessionRecord> sessions = new ConcurrentHashMap<>();
  private final Map<String, CaptchaRecord> captchas = new ConcurrentHashMap<>();
  private final EmailService emailService;

  public InMemoryCareerService(EmailService emailService) {
    this.emailService = emailService;
  }

  @PostConstruct
  public void seed() {
    // Start with an empty project. Users, jobs, resumes, applications, and meetings are created from the UI.
  }

  public Map<String, Object> issueCaptcha() {
    pruneExpiredCaptchas();
    String challenge = String.format("%05d", (int) (Math.random() * 100000));
    String token = UUID.randomUUID().toString();
    long expiresAt = System.currentTimeMillis() + CAPTCHA_DURATION_MS;
    captchas.put(token, new CaptchaRecord(challenge, expiresAt));
    return Map.of("token", token, "challenge", challenge, "expiresAt", expiresAt);
  }

  public Map<String, Object> login(String email, String password, String captchaToken, String captchaAnswer) {
    validateCaptcha(captchaToken, captchaAnswer);
    UserAccount user = users.values().stream()
      .filter(candidate -> candidate.email.equalsIgnoreCase(email))
      .findFirst()
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
    if (!Objects.equals(user.password, password)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }
    return createAuthPayload(user);
  }

  public Map<String, Object> register(String name, String email, String password, String role, String captchaToken, String captchaAnswer) {
    validateCaptcha(captchaToken, captchaAnswer);
    if (users.values().stream().anyMatch(user -> user.email.equalsIgnoreCase(email))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
    }
    UserAccount user = createUser(name, email, password, role);
    if ("student".equals(user.role)) {
      user.department = "Computer Science";
      user.cgpa = 8.0;
    }
    sendRegistrationEmail(user);
    return createAuthPayload(user);
  }

  public Map<String, Object> getCurrentUser(String authorization) {
    return toUserResponse(requireUser(authorization));
  }

  public Map<String, Object> updateCurrentUser(String authorization, Map<String, Object> updates) {
    UserAccount user = requireUser(authorization);
    user.name = stringValue(updates.get("name"), user.name);
    user.department = stringValue(updates.get("department"), user.department);
    user.phone = stringValue(updates.get("phone"), user.phone);
    user.companyName = stringValue(updates.get("companyName"), user.companyName);
    user.company = user.companyName;
    Object cgpaValue = updates.get("cgpa");
    if (cgpaValue != null && !(cgpaValue instanceof String text && text.isBlank())) {
      user.cgpa = numberValue(cgpaValue);
    }
    return toUserResponse(user);
  }

  public List<Map<String, Object>> getAllUsers(String authorization) {
    requireRole(authorization, Set.of("officer"));
    return users.values().stream()
      .sorted(Comparator.comparingLong(user -> user.id))
      .map(this::toUserResponse)
      .toList();
  }

  public Map<String, Object> updateUserRole(String authorization, long userId, String role) {
    requireRole(authorization, Set.of("officer"));
    UserAccount user = getUserById(userId);
    user.role = normalizeRole(role);
    return toUserResponse(user);
  }

  public void deleteUser(String authorization, long userId) {
    UserAccount actor = requireRole(authorization, Set.of("officer"));
    if (actor.id == userId) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot delete the currently logged in placement officer");
    }
    users.remove(userId);
    resumes.remove(userId);
    applications.entrySet().removeIf(entry -> entry.getValue().studentId == userId || entry.getValue().employerId == userId);
    jobs.entrySet().removeIf(entry -> entry.getValue().employerId == userId);
    interactions.entrySet().removeIf(entry -> Objects.equals(entry.getValue().studentId, userId) || Objects.equals(entry.getValue().employerId, userId));
    sessions.entrySet().removeIf(entry -> entry.getValue().userId == userId);
  }

  public List<Map<String, Object>> getJobs(String authorization) {
    UserAccount actor = optionalUser(authorization);
    if (actor != null && "employer".equals(actor.role)) {
      return jobs.values().stream()
        .filter(job -> job.employerId == actor.id)
        .sorted(Comparator.comparing(job -> job.createdAt, Comparator.reverseOrder()))
        .map(job -> toJobResponse(job, null))
        .toList();
    }
    return jobs.values().stream()
      .sorted(Comparator.comparing(job -> job.createdAt, Comparator.reverseOrder()))
      .map(job -> toJobResponse(job, null))
      .toList();
  }

  public List<Map<String, Object>> getMatchedJobs(String authorization) {
    UserAccount student = requireRole(authorization, Set.of("student"));
    if (resumes.get(student.id) == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload your resume first");
    }
    return jobs.values().stream()
      .map(job -> Map.entry(job, calculateMatchScore(student.skills, job.skillsRequired)))
      .filter(entry -> entry.getValue() > 0)
      .filter(entry -> entry.getKey().minimumCgpa == null || student.cgpa == null || student.cgpa >= entry.getKey().minimumCgpa)
      .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
      .map(entry -> toJobResponse(entry.getKey(), entry.getValue()))
      .toList();
  }

  public Map<String, Object> createJob(String authorization, Map<String, Object> payload) {
    UserAccount employer = requireRole(authorization, Set.of("employer"));
    JobPosting job = createJob(
      employer,
      requiredString(payload, "title"),
      requiredString(payload, "description"),
      requiredString(payload, "location"),
      requiredString(payload, "employmentType"),
      requiredString(payload, "packageOffered"),
      integerValue(payload.get("openings"), 1),
      nullableNumber(payload.get("minimumCgpa")),
      nullableString(payload.get("applicationDeadline")),
      parseSkills(payload.get("skillsRequired"))
    );
    return toJobResponse(job, null);
  }

  public Map<String, Object> updateJob(String authorization, long jobId, Map<String, Object> payload) {
    UserAccount employer = requireRole(authorization, Set.of("employer"));
    JobPosting job = getJobById(jobId);
    if (job.employerId != employer.id) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only edit your own jobs");
    }
    job.title = requiredString(payload, "title");
    job.description = requiredString(payload, "description");
    job.location = requiredString(payload, "location");
    job.employmentType = requiredString(payload, "employmentType");
    job.packageOffered = requiredString(payload, "packageOffered");
    job.openings = integerValue(payload.get("openings"), job.openings);
    job.minimumCgpa = nullableNumber(payload.get("minimumCgpa"));
    job.applicationDeadline = nullableString(payload.get("applicationDeadline"));
    job.skillsRequired = parseSkills(payload.get("skillsRequired"));
    return toJobResponse(job, null);
  }

  public void deleteJob(String authorization, long jobId) {
    UserAccount employer = requireRole(authorization, Set.of("employer"));
    JobPosting job = getJobById(jobId);
    if (job.employerId != employer.id) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only delete your own jobs");
    }
    jobs.remove(jobId);
    applications.entrySet().removeIf(entry -> entry.getValue().jobId == jobId);
  }

  public Map<String, Object> apply(String authorization, Long jobId, Long studentIdFromLegacy) {
    UserAccount student = authorization != null ? requireRole(authorization, Set.of("student")) : getUserById(studentIdFromLegacy);
    if (!"student".equals(student.role)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Applications can only be created for student accounts");
    }
    if (resumes.get(student.id) == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload your resume first");
    }

    JobPosting job = getJobById(jobId);
    boolean exists = applications.values().stream()
      .anyMatch(application -> application.studentId == student.id && application.jobId == jobId);
    if (exists) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "You already applied for this job");
    }
    return toApplicationResponse(createApplication(student, job));
  }

  public List<Map<String, Object>> getApplications(String authorization) {
    UserAccount actor = optionalUser(authorization);
    Collection<PlacementApplication> source;
    if (actor == null) {
      source = applications.values();
    } else if ("student".equals(actor.role)) {
      source = applications.values().stream().filter(application -> application.studentId == actor.id).toList();
    } else if ("employer".equals(actor.role)) {
      source = applications.values().stream().filter(application -> application.employerId == actor.id).toList();
    } else {
      source = applications.values();
    }

    return source.stream()
      .sorted(Comparator.comparing(application -> application.appliedAt, Comparator.reverseOrder()))
      .map(this::toApplicationResponse)
      .toList();
  }

  public List<Map<String, Object>> getStudentApplications(String authorization) {
    UserAccount student = requireRole(authorization, Set.of("student"));
    return applications.values().stream()
      .filter(application -> application.studentId == student.id)
      .sorted(Comparator.comparing(application -> application.appliedAt, Comparator.reverseOrder()))
      .map(this::toApplicationResponse)
      .toList();
  }

  public List<Map<String, Object>> getEmployerApplications(String authorization) {
    UserAccount employer = requireRole(authorization, Set.of("employer"));
    return applications.values().stream()
      .filter(application -> application.employerId == employer.id)
      .sorted(Comparator.comparing(application -> application.appliedAt, Comparator.reverseOrder()))
      .map(this::toApplicationResponse)
      .toList();
  }

  public Map<String, Object> updateApplicationStatus(String authorization, long applicationId, String status) {
    UserAccount actor = requireUser(authorization);
    PlacementApplication application = getApplicationById(applicationId);
    if ("employer".equals(actor.role) && application.employerId != actor.id) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only update applications for your own jobs");
    }
    if (!Set.of("employer", "officer").contains(actor.role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not allowed to update application statuses");
    }

    application.status = normalizeStatus(status);
    if ("SHORTLISTED".equals(application.status)) {
      application.interviewDate = LocalDateTime.now().plusDays(5).toLocalDate().toString();
    }
    if ("SELECTED".equals(application.status)) {
      application.offerLetter = application.jobTitle + " offer from " + application.company + " - " + getJobById(application.jobId).packageOffered;
    }
    sendApplicationStatusEmail(application);
    return toApplicationResponse(application);
  }

  public void deleteApplication(String authorization, long applicationId) {
    UserAccount actor = optionalUser(authorization);
    PlacementApplication application = getApplicationById(applicationId);
    if (actor != null) {
      boolean canDelete = "officer".equals(actor.role) || application.studentId == actor.id;
      if (!canDelete) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot delete this application");
      }
    }
    applications.remove(applicationId);
  }

  public Map<String, Object> getResume(String authorization) {
    UserAccount student = requireRole(authorization, Set.of("student"));
    ResumeRecord resume = resumes.get(student.id);
    if (resume == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resume not found");
    }
    return toResumeResponse(resume);
  }

  public Map<String, Object> uploadResume(String authorization, String fileName, String contentType) {
    UserAccount student = requireRole(authorization, Set.of("student"));
    String resolvedFileName = fileName == null || fileName.isBlank() ? "resume.pdf" : fileName;
    String resolvedContentType = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
    List<String> skills = extractSkillsFromFilename(resolvedFileName);
    if (skills.isEmpty()) {
      skills = new ArrayList<>(student.skills.isEmpty() ? FALLBACK_RESUME_SKILLS : student.skills);
    }
    return toResumeResponse(createResume(student, resolvedFileName, resolvedContentType, skills, LocalDateTime.now().format(DATE_TIME_FORMATTER)));
  }

  public void deleteResume(String authorization) {
    UserAccount student = requireRole(authorization, Set.of("student"));
    resumes.remove(student.id);
    student.resumeUploaded = false;
    student.resumeName = null;
    student.resumeUrl = null;
    student.skills = new ArrayList<>();
  }

  public Map<String, Object> getSummary(String authorization) {
    requireRole(authorization, Set.of("officer"));
    return buildSummary();
  }

  public List<Map<String, Object>> getPlacements(String authorization) {
    requireRole(authorization, Set.of("officer"));
    return applications.values().stream()
      .filter(application -> "SELECTED".equals(application.status))
      .sorted(Comparator.comparing(application -> application.appliedAt, Comparator.reverseOrder()))
      .map(this::toApplicationResponse)
      .toList();
  }

  public List<Map<String, Object>> getInteractions(String authorization) {
    requireRole(authorization, Set.of("officer"));
    return interactions.values().stream()
      .sorted(Comparator.comparing(interaction -> interaction.interactionDate, Comparator.reverseOrder()))
      .map(this::toInteractionResponse)
      .toList();
  }

  public List<Map<String, Object>> getStudentMeetings(String authorization) {
    UserAccount student = requireRole(authorization, Set.of("student"));
    return interactions.values().stream()
      .filter(interaction -> "SCHEDULED".equals(interaction.status))
      .filter(interaction -> interaction.studentId == null || Objects.equals(interaction.studentId, student.id))
      .sorted(Comparator.comparing(interaction -> interaction.interactionDate))
      .map(interaction -> toInteractionResponse(interaction, student.id))
      .toList();
  }

  public Map<String, Object> requestMeetingJoin(String authorization, long interactionId) {
    UserAccount student = requireRole(authorization, Set.of("student"));
    OfficerInteraction interaction = getInteractionById(interactionId);
    if (!"SCHEDULED".equals(interaction.status)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only scheduled meetings can be joined");
    }
    if (interaction.studentId != null && !Objects.equals(interaction.studentId, student.id)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This meeting is not assigned to you");
    }
    if (!interaction.admittedParticipantIds.contains(student.id) && !interaction.waitingParticipantIds.contains(student.id)) {
      interaction.waitingParticipantIds.add(student.id);
      sendMeetingJoinRequestEmail(interaction, student);
    }
    return toInteractionResponse(interaction, student.id);
  }

  public Map<String, Object> createInteraction(String authorization, Map<String, Object> payload) {
    UserAccount officer = requireRole(authorization, Set.of("officer"));
    Long studentId = nullableLong(payload.get("studentId"));
    Long employerId = nullableLong(payload.get("employerId"));
    OfficerInteraction interaction = createInteraction(
      requiredString(payload, "title"),
      requiredString(payload, "description"),
      normalizeInteractionStatus(requiredString(payload, "status")),
      requiredString(payload, "interactionDate"),
      officer,
      studentId == null ? null : getUserById(studentId),
      employerId == null ? null : getUserById(employerId)
    );
    sendMeetingScheduledEmails(interaction);
    return toInteractionResponse(interaction);
  }

  public void deleteInteraction(String authorization, long interactionId) {
    requireRole(authorization, Set.of("officer"));
    if (interactions.remove(interactionId) == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Interaction not found");
    }
  }

  public Map<String, Object> admitMeetingParticipant(String authorization, long interactionId, long studentId) {
    requireRole(authorization, Set.of("officer"));
    UserAccount student = getUserById(studentId);
    if (!"student".equals(student.role)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only student participants can be admitted");
    }
    OfficerInteraction interaction = getInteractionById(interactionId);
    interaction.waitingParticipantIds.remove(studentId);
    if (!interaction.admittedParticipantIds.contains(studentId)) {
      interaction.admittedParticipantIds.add(studentId);
    }
    sendMeetingAdmissionEmail(interaction, student);
    return toInteractionResponse(interaction);
  }

  public Map<String, Object> getAdminOverview(String authorization) {
    requireRole(authorization, Set.of("officer"));
    return buildSummary();
  }

  private void sendRegistrationEmail(UserAccount user) {
    String dashboardUrl = emailService.getFrontendUrl() + "/" + user.role;
    emailService.send(
      user.email,
      "PlaceIT Hub account created",
      "Hello " + user.name + ",\n\n"
        + "Your PlaceIT Hub account has been created successfully.\n"
        + "Role: " + displayRole(user.role) + "\n\n"
        + "Open your dashboard here:\n" + dashboardUrl + "\n\n"
        + "Regards,\nPlaceIT Hub"
    );
  }

  private void sendApplicationStatusEmail(PlacementApplication application) {
    if (!Set.of("SHORTLISTED", "SELECTED", "REJECTED").contains(application.status)) {
      return;
    }

    UserAccount student = users.get(application.studentId);
    if (student == null) {
      return;
    }

    String details = "Job: " + application.jobTitle + "\n"
      + "Company: " + application.company + "\n"
      + "Status: " + application.status + "\n";
    if (application.interviewDate != null) {
      details += "Interview date: " + application.interviewDate + "\n";
    }
    if (application.offerLetter != null) {
      details += "Offer: " + application.offerLetter + "\n";
    }

    emailService.send(
      student.email,
      "Application status updated: " + application.status,
      "Hello " + student.name + ",\n\n"
        + "Your placement application status has been updated.\n\n"
        + details + "\n"
        + "View your applications here:\n" + emailService.getFrontendUrl() + "/student/applications\n\n"
        + "Regards,\nPlaceIT Hub"
    );
  }

  private void sendMeetingScheduledEmails(OfficerInteraction interaction) {
    if (!"SCHEDULED".equals(interaction.status)) {
      return;
    }

    List<UserAccount> recipients;
    if (interaction.studentId == null) {
      recipients = users.values().stream()
        .filter(user -> "student".equals(user.role))
        .sorted(Comparator.comparingLong(user -> user.id))
        .toList();
    } else {
      UserAccount student = users.get(interaction.studentId);
      recipients = student == null ? List.of() : List.of(student);
    }

    for (UserAccount student : recipients) {
      emailService.send(
        student.email,
        "Placement meeting scheduled: " + interaction.title,
        "Hello " + student.name + ",\n\n"
          + "A placement meeting has been scheduled for you.\n\n"
          + meetingDetails(interaction)
          + "\nYou can open the meeting link from this email:\n" + interaction.meetingUrl + "\n\n"
          + "You can also manage meeting access from your dashboard:\n" + emailService.getFrontendUrl() + "/student\n\n"
          + "Regards,\nPlaceIT Hub"
      );
    }
  }

  private void sendMeetingJoinRequestEmail(OfficerInteraction interaction, UserAccount student) {
    UserAccount officer = users.get(interaction.officerId);
    if (officer == null) {
      return;
    }

    emailService.send(
      officer.email,
      "Meeting join request from " + student.name,
      "Hello " + officer.name + ",\n\n"
        + student.name + " requested access to a placement meeting.\n\n"
        + meetingDetails(interaction)
        + "\nAdmit the participant from the officer interactions page:\n"
        + emailService.getFrontendUrl() + "/officer/interactions\n\n"
        + "Regards,\nPlaceIT Hub"
    );
  }

  private void sendMeetingAdmissionEmail(OfficerInteraction interaction, UserAccount student) {
    emailService.send(
      student.email,
      "You have been admitted to the meeting",
      "Hello " + student.name + ",\n\n"
        + "The placement officer admitted you to the meeting.\n\n"
        + meetingDetails(interaction)
        + "\nJoin from this email:\n" + interaction.meetingUrl + "\n\n"
        + "Regards,\nPlaceIT Hub"
    );
  }

  private String meetingDetails(OfficerInteraction interaction) {
    return "Meeting: " + interaction.title + "\n"
      + "Description: " + interaction.description + "\n"
      + "Date and time: " + interaction.interactionDate + "\n"
      + "Officer: " + interaction.officerName + "\n";
  }

  private String displayRole(String role) {
    return switch (role) {
      case "student" -> "Student";
      case "employer" -> "Employer";
      case "officer" -> "Placement Officer";
      default -> role;
    };
  }

  private Map<String, Object> createAuthPayload(UserAccount user) {
    String token = UUID.randomUUID().toString();
    long expiresAt = System.currentTimeMillis() + SESSION_DURATION_MS;
    sessions.put(token, new SessionRecord(user.id, expiresAt));
    return Map.of("token", token, "sessionExpiresAt", expiresAt, "user", toUserResponse(user));
  }

  private void validateCaptcha(String token, String answer) {
    pruneExpiredCaptchas();
    CaptchaRecord captcha = captchas.remove(token);
    if (captcha == null || captcha.expiresAt < System.currentTimeMillis()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Captcha expired. Please refresh and try again");
    }
    if (!captcha.answer.equalsIgnoreCase(answer == null ? "" : answer.trim())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Incorrect captcha answer");
    }
  }

  private void pruneExpiredCaptchas() {
    long now = System.currentTimeMillis();
    captchas.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
  }

  private UserAccount optionalUser(String authorization) {
    if (authorization == null || authorization.isBlank()) {
      return null;
    }
    return requireUser(authorization);
  }

  private UserAccount requireUser(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid authorization token");
    }
    String token = authorization.substring("Bearer ".length()).trim();
    SessionRecord session = sessions.get(token);
    if (session == null || session.expiresAt < System.currentTimeMillis()) {
      sessions.remove(token);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expired. Please login again");
    }
    return getUserById(session.userId);
  }

  private UserAccount requireRole(String authorization, Set<String> roles) {
    UserAccount user = requireUser(authorization);
    if (!roles.contains(user.role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to access this resource");
    }
    return user;
  }

  private UserAccount createUser(String name, String email, String password, String role) {
    UserAccount user = new UserAccount();
    user.id = userIds.incrementAndGet();
    user.name = name;
    user.email = email.toLowerCase(Locale.ROOT);
    user.password = password;
    user.role = normalizeRole(role);
    user.active = true;
    user.resumeUploaded = false;
    user.skills = new ArrayList<>();
    users.put(user.id, user);
    return user;
  }

  private ResumeRecord createResume(UserAccount student, String fileName, String contentType, List<String> skills, String uploadedAt) {
    ResumeRecord resume = new ResumeRecord();
    resume.id = resumeIds.incrementAndGet();
    resume.studentId = student.id;
    resume.fileName = fileName;
    resume.filePath = "/mock-storage/" + student.id + "/" + fileName.replace(" ", "_");
    resume.contentType = contentType;
    resume.skills = new ArrayList<>(skills);
    resume.uploadedAt = uploadedAt;
    resumes.put(student.id, resume);

    student.resumeUploaded = true;
    student.resumeName = fileName;
    student.resumeUrl = resume.filePath;
    student.skills = new ArrayList<>(skills);
    return resume;
  }

  private JobPosting createJob(UserAccount employer, String title, String description, String location, String employmentType,
                               String packageOffered, int openings, Double minimumCgpa, String applicationDeadline, List<String> skillsRequired) {
    JobPosting job = new JobPosting();
    job.id = jobIds.incrementAndGet();
    job.title = title;
    job.description = description;
    job.location = location;
    job.employmentType = employmentType;
    job.packageOffered = packageOffered;
    job.openings = openings;
    job.minimumCgpa = minimumCgpa;
    job.applicationDeadline = applicationDeadline;
    job.skillsRequired = new ArrayList<>(skillsRequired);
    job.createdAt = LocalDateTime.now().minusDays(Math.max(0, 5 - (int) job.id)).format(DATE_TIME_FORMATTER);
    job.employerId = employer.id;
    job.employerName = employer.companyName != null ? employer.companyName : employer.name;
    jobs.put(job.id, job);
    return job;
  }

  private PlacementApplication createApplication(UserAccount student, JobPosting job) {
    PlacementApplication application = new PlacementApplication();
    application.id = applicationIds.incrementAndGet();
    application.jobId = job.id;
    application.jobTitle = job.title;
    application.company = job.employerName;
    application.studentId = student.id;
    application.studentName = student.name;
    application.employerId = job.employerId;
    application.status = "APPLIED";
    application.appliedAt = LocalDateTime.now().minusDays(Math.max(0, 6 - (int) application.id)).format(DATE_TIME_FORMATTER);
    applications.put(application.id, application);
    return application;
  }

  private OfficerInteraction createInteraction(String title, String description, String status, String interactionDate,
                                               UserAccount officer, UserAccount student, UserAccount employer) {
    OfficerInteraction interaction = new OfficerInteraction();
    interaction.id = interactionIds.incrementAndGet();
    interaction.title = title;
    interaction.description = description;
    interaction.status = status;
    interaction.interactionDate = interactionDate;
    interaction.officerId = officer.id;
    interaction.officerName = officer.name;
    interaction.studentId = student == null ? null : student.id;
    interaction.studentName = student == null ? null : student.name;
    interaction.employerId = employer == null ? null : employer.id;
    interaction.employerName = employer == null ? null : (employer.companyName != null ? employer.companyName : employer.name);
    interaction.meetingUrl = "https://meet.jit.si/placeit-" + interaction.id + "-" + title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    interactions.put(interaction.id, interaction);
    return interaction;
  }

  private UserAccount getUserById(Long userId) {
    if (userId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User id is required");
    }
    UserAccount user = users.get(userId);
    if (user == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
    }
    return user;
  }

  private JobPosting getJobById(Long jobId) {
    if (jobId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Job id is required");
    }
    JobPosting job = jobs.get(jobId);
    if (job == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
    }
    return job;
  }

  private PlacementApplication getApplicationById(long applicationId) {
    PlacementApplication application = applications.get(applicationId);
    if (application == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found");
    }
    return application;
  }

  private OfficerInteraction getInteractionById(long interactionId) {
    OfficerInteraction interaction = interactions.get(interactionId);
    if (interaction == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Interaction not found");
    }
    return interaction;
  }

  private Map<String, Object> buildSummary() {
    long selectedCount = applications.values().stream().filter(application -> "SELECTED".equals(application.status)).count();
    long rejectedCount = applications.values().stream().filter(application -> "REJECTED".equals(application.status)).count();
    long resumesUploaded = users.values().stream().filter(user -> user.resumeUploaded).count();
    long studentsApplied = applications.values().stream().map(application -> application.studentId).distinct().count();
    long studentsPlaced = applications.values().stream()
      .filter(application -> "SELECTED".equals(application.status))
      .map(application -> application.studentId)
      .distinct()
      .count();

    return Map.of(
      "totalStudents", users.values().stream().filter(user -> "student".equals(user.role)).count(),
      "totalEmployers", users.values().stream().filter(user -> "employer".equals(user.role)).count(),
      "totalJobs", jobs.size(),
      "totalApplications", applications.size(),
      "studentsApplied", studentsApplied,
      "studentsPlaced", studentsPlaced,
      "selectedCount", selectedCount,
      "rejectedCount", rejectedCount,
      "resumesUploaded", resumesUploaded
    );
  }

  private Map<String, Object> toUserResponse(UserAccount user) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", user.id);
    payload.put("name", user.name);
    payload.put("email", user.email);
    payload.put("role", user.role);
    payload.put("department", user.department);
    payload.put("phone", user.phone);
    payload.put("companyName", user.companyName);
    payload.put("company", user.company);
    payload.put("cgpa", user.cgpa);
    payload.put("active", user.active);
    payload.put("resumeUploaded", user.resumeUploaded);
    payload.put("skills", user.skills);
    payload.put("resumeUrl", user.resumeUrl);
    payload.put("resumeName", user.resumeName);
    return payload;
  }

  private Map<String, Object> toResumeResponse(ResumeRecord resume) {
    return Map.of(
      "id", resume.id,
      "fileName", resume.fileName,
      "filePath", resume.filePath,
      "contentType", resume.contentType,
      "skills", resume.skills,
      "uploadedAt", resume.uploadedAt
    );
  }

  private Map<String, Object> toJobResponse(JobPosting job, Integer matchScore) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", job.id);
    payload.put("title", job.title);
    payload.put("description", job.description);
    payload.put("location", job.location);
    payload.put("employmentType", job.employmentType);
    payload.put("packageOffered", job.packageOffered);
    payload.put("openings", job.openings);
    payload.put("minimumCgpa", job.minimumCgpa);
    payload.put("applicationDeadline", job.applicationDeadline);
    payload.put("skillsRequired", job.skillsRequired);
    payload.put("createdAt", job.createdAt);
    payload.put("employerId", job.employerId);
    payload.put("employerName", job.employerName);
    payload.put("matchScore", matchScore);
    payload.put("company", job.employerName);
    payload.put("package", job.packageOffered);
    payload.put("type", job.employmentType);
    payload.put("skills", job.skillsRequired);
    payload.put("deadline", job.applicationDeadline);
    payload.put("postedDate", job.createdAt);
    payload.put("requiredCGPA", job.minimumCgpa);
    payload.put("requiredSkills", job.skillsRequired);
    return payload;
  }

  private Map<String, Object> toApplicationResponse(PlacementApplication application) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", application.id);
    payload.put("jobId", application.jobId);
    payload.put("jobTitle", application.jobTitle);
    payload.put("company", application.company);
    payload.put("studentId", application.studentId);
    payload.put("studentName", application.studentName);
    payload.put("status", application.status);
    payload.put("appliedAt", application.appliedAt);
    payload.put("appliedDate", application.appliedAt.substring(0, 10));
    payload.put("interviewDate", application.interviewDate);
    payload.put("offerLetter", application.offerLetter);
    return payload;
  }

  private Map<String, Object> toInteractionResponse(OfficerInteraction interaction) {
    return toInteractionResponse(interaction, null);
  }

  private Map<String, Object> toInteractionResponse(OfficerInteraction interaction, Long viewerStudentId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", interaction.id);
    payload.put("title", interaction.title);
    payload.put("description", interaction.description);
    payload.put("status", interaction.status);
    payload.put("interactionDate", interaction.interactionDate);
    payload.put("officerId", interaction.officerId);
    payload.put("officerName", interaction.officerName);
    payload.put("studentId", interaction.studentId);
    payload.put("studentName", interaction.studentName);
    payload.put("employerId", interaction.employerId);
    payload.put("employerName", interaction.employerName);
    payload.put("meetingUrl", interaction.meetingUrl);
    payload.put("waitingParticipants", interaction.waitingParticipantIds.stream().map(this::toParticipantResponse).toList());
    payload.put("admittedParticipants", interaction.admittedParticipantIds.stream().map(this::toParticipantResponse).toList());
    if (viewerStudentId != null) {
      payload.put("joinStatus", interaction.admittedParticipantIds.contains(viewerStudentId) ? "ADMITTED" : interaction.waitingParticipantIds.contains(viewerStudentId) ? "WAITING" : "NOT_REQUESTED");
    }
    return payload;
  }

  private Map<String, Object> toParticipantResponse(Long userId) {
    UserAccount user = users.get(userId);
    if (user == null) {
      return Map.of("id", userId, "name", "Removed student", "email", "");
    }
    return Map.of("id", user.id, "name", user.name, "email", user.email);
  }

  private int calculateMatchScore(List<String> studentSkills, List<String> jobSkills) {
    if (studentSkills == null || studentSkills.isEmpty() || jobSkills == null || jobSkills.isEmpty()) {
      return 0;
    }
    Set<String> normalizedStudentSkills = studentSkills.stream()
      .map(skill -> skill.toLowerCase(Locale.ROOT))
      .collect(Collectors.toSet());
    long overlaps = jobSkills.stream()
      .map(skill -> skill.toLowerCase(Locale.ROOT))
      .filter(normalizedStudentSkills::contains)
      .count();
    if (overlaps == 0) {
      return 0;
    }
    return (int) Math.round((overlaps * 100.0) / jobSkills.size());
  }

  private List<String> parseSkills(Object value) {
    if (value instanceof Collection<?> items) {
      return items.stream()
        .map(String::valueOf)
        .map(String::trim)
        .filter(item -> !item.isEmpty())
        .map(item -> item.toLowerCase(Locale.ROOT))
        .distinct()
        .toList();
    }
    if (value instanceof String text) {
      return List.of(text.split(",")).stream()
        .map(String::trim)
        .filter(item -> !item.isEmpty())
        .map(item -> item.toLowerCase(Locale.ROOT))
        .distinct()
        .toList();
    }
    return new ArrayList<>(FALLBACK_RESUME_SKILLS);
  }

  private List<String> extractSkillsFromFilename(String fileName) {
    String normalized = fileName.toLowerCase(Locale.ROOT);
    List<String> knownSkills = List.of(
      "java", "spring boot", "react", "typescript", "javascript", "mysql", "sql",
      "python", "machine learning", "node", "html", "css"
    );
    return knownSkills.stream().filter(normalized::contains).distinct().toList();
  }

  private String normalizeRole(String role) {
    String normalized = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
    if (!Set.of("student", "employer", "officer").contains(normalized)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role");
    }
    return normalized;
  }

  private String normalizeStatus(String status) {
    String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("APPLIED", "SHORTLISTED", "REJECTED", "SELECTED", "OFFERED", "ACCEPTED", "DECLINED").contains(normalized)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid application status");
    }
    return normalized;
  }

  private String normalizeInteractionStatus(String status) {
    String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("SCHEDULED", "COMPLETED", "CANCELLED").contains(normalized)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid interaction status");
    }
    return normalized;
  }

  private String requiredString(Map<String, Object> payload, String key) {
    String value = nullableString(payload.get(key));
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " is required");
    }
    return value;
  }

  private String nullableString(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  private String stringValue(Object value, String fallback) {
    String text = nullableString(value);
    return text == null ? fallback : text;
  }

  private Double nullableNumber(Object value) {
    if (value == null || value instanceof String text && text.isBlank()) {
      return null;
    }
    return numberValue(value);
  }

  private Double numberValue(Object value) {
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    return Double.parseDouble(String.valueOf(value));
  }

  private Long nullableLong(Object value) {
    if (value == null || value instanceof String text && text.isBlank()) {
      return null;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    return Long.parseLong(String.valueOf(value));
  }

  private int integerValue(Object value, int fallback) {
    if (value == null || value instanceof String text && text.isBlank()) {
      return fallback;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    return Integer.parseInt(String.valueOf(value));
  }

  private static final class UserAccount {
    private long id;
    private String name;
    private String email;
    private String password;
    private String role;
    private String department;
    private String phone;
    private String companyName;
    private String company;
    private Double cgpa;
    private boolean active;
    private boolean resumeUploaded;
    private List<String> skills = new ArrayList<>();
    private String resumeUrl;
    private String resumeName;
  }

  private static final class ResumeRecord {
    private long id;
    private long studentId;
    private String fileName;
    private String filePath;
    private String contentType;
    private List<String> skills = new ArrayList<>();
    private String uploadedAt;
  }

  private static final class JobPosting {
    private long id;
    private String title;
    private String description;
    private String location;
    private String employmentType;
    private String packageOffered;
    private int openings;
    private Double minimumCgpa;
    private String applicationDeadline;
    private List<String> skillsRequired = new ArrayList<>();
    private String createdAt;
    private long employerId;
    private String employerName;
  }

  private static final class PlacementApplication {
    private long id;
    private long jobId;
    private String jobTitle;
    private String company;
    private long studentId;
    private String studentName;
    private long employerId;
    private String status;
    private String appliedAt;
    private String interviewDate;
    private String offerLetter;
  }

  private static final class OfficerInteraction {
    private long id;
    private String title;
    private String description;
    private String status;
    private String interactionDate;
    private long officerId;
    private String officerName;
    private Long studentId;
    private String studentName;
    private Long employerId;
    private String employerName;
    private String meetingUrl;
    private List<Long> waitingParticipantIds = new ArrayList<>();
    private List<Long> admittedParticipantIds = new ArrayList<>();
  }

  private record SessionRecord(long userId, long expiresAt) { }

  private record CaptchaRecord(String answer, long expiresAt) { }
}
