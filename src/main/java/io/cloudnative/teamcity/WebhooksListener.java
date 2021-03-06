package io.cloudnative.teamcity;

import static io.cloudnative.teamcity.WebhookPayload.*;
import static io.cloudnative.teamcity.WebhooksConstants.*;
import static io.cloudnative.teamcity.WebhooksUtils.*;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.google.gson.*;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.artifacts.ArtifactsGuard;
import jetbrains.buildServer.vcs.*;
import jetbrains.buildServer.BuildProblemData;
import jodd.http.HttpRequest;
import jodd.http.net.SocketHttpConnection;
import lombok.*;
import lombok.experimental.ExtensionMethod;
import lombok.experimental.FieldDefaults;
import java.io.File;
import java.util.*;
import java.text.DateFormat;


@ExtensionMethod(LombokExtensions.class)
@AllArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class WebhooksListener extends BuildServerAdapter {

  @NonNull WebhooksSettings settings;
  @NonNull SBuildServer     buildServer;
  @NonNull ServerPaths      serverPaths;
  @NonNull ArtifactsGuard   artifactsGuard;

  String DateFormat = "yyyy-MM-dd'T'HH:mm:ssZ";
  Gson gson = new GsonBuilder().setPrettyPrinting().setDateFormat(DateFormat).serializeNulls().create();

  public void register(){
    buildServer.addListener(this);
  }


  @Override
  public void buildFinished(@NonNull SRunningBuild build) {
    val time = System.currentTimeMillis();
    try {
      Date started_at = build.getStartDate();
      Date finished_at = build.getFinishDate();
      if (finished_at == null) {
        finished_at = new Date();
      }
      String status = build.getStatusDescriptor().getStatus().getText().toLowerCase();
      if (build.isInterrupted()) {
        if (build.getCanceledInfo() != null) {
          status = "cancelled";
        } else {
          status = "error";
        }
      }

      // set "error" status if failure is during source checkout (a.k.a. gitcrap)
      for (BuildProblemData problem : build.getFailureReasons()) {
        if (problem.getIdentity().equals("gitcrap")) {
          status = "error";
          log("Setting payload status to \"error\" as failure is during source checkout.");
          break;
        }
      }

      if (build.isInternalError()) {
        status = "error";
      }

      val payload = gson.toJson(buildPayload(build, status, started_at, finished_at));
      gson.fromJson(payload, Map.class); // Sanity check of JSON generated
      log("Build '%s/#%s' finished, payload is '%s'".f(build.getFullName(), build.getBuildNumber(), payload));

      /////////////////////////////////////////////////
      // log("state: " + build.getBuildStatus().toString().toLowerCase());
      // log("duration: " + String.valueOf(build.getDuration()) + "s");
      //
      // log("Revisions:");
      // for (val rev : build.getRevisions()) {
      //   log(rev.getRevision());
      // }
      // log("Containing changes:");
      // for (VcsModification rev : build.getChanges(SelectPrevBuildPolicy.SINCE_LAST_SUCCESSFULLY_FINISHED_BUILD, false)) {
      //   log(rev.getVersion());
      // }
      // for (Map.Entry<String, String> entry : build.getParametersProvider().getAll().entrySet()) {
      //   String key = entry.getKey().toString();
      //   String value = entry.getValue().toString();
      //   log("key, " + key + " value " + value );
      // }
      /////////////////////////////////////////////////

      if (build.isPersonal()) {
        log("Skipping post for personal build.");
      } else {
        for (val url : settings.getUrls(build.getProjectExternalId())){
          postPayload(url, payload);
        }
      }

      log("Operation finished in %s ms".f(System.currentTimeMillis() - time));
    }
    catch (Throwable t) {
      error("Failed to listen on buildFinished() of '%s' #%s".f(build.getFullName(), build.getBuildNumber()), t);
    }
  }


  @Override
  public void changesLoaded(@NonNull SRunningBuild build) {
    val time = System.currentTimeMillis();
    try {
      Date started_at = build.getStartDate();
      String status = "pending";

      val payload = gson.toJson(buildPayload(build, status, started_at, null));
      gson.fromJson(payload, Map.class); // Sanity check of JSON generated
      log("Build '%s/#%s' started, payload is '%s'".f(build.getFullName(), build.getBuildNumber(), payload));

      /////////////////////////////////////////////////
      // log("state: started");
      // log("Revision:");
      // String head = "";
      // val revisions = build.getRevisions();
      // if (revisions.isEmpty() == false) {
      //   head = revisions.get(0).getRevision();
      // }
      // else {
      //   // if above fails, fall back to getting version from vcs root
      //   val vcsRoots = build.getBuildType().getVcsRootInstanceEntries();
      //   if (! vcsRoots.isEmpty()) {
      //     val vcsRoot = vcsRoots.get(0).getVcsRoot();
      //     head = vcsRoot.getCurrentRevision().getVersion();
      //   }
      // }
      // log(head);
      // log("Containing changes:");
      // for (VcsModification rev : build.getChanges(SelectPrevBuildPolicy.SINCE_LAST_SUCCESSFULLY_FINISHED_BUILD, false)) {
      //   log(rev.getVersion());
      // }
      /////////////////////////////////////////////////

      if (build.isPersonal()) {
        log("Skipping post for personal build.");
      } else {
        for (val url : settings.getUrls(build.getProjectExternalId())){
          postPayload(url, payload);
        }
      }

      log("Operation finished in %s ms".f(System.currentTimeMillis() - time));
    }
    catch (Throwable t) {
      error("Failed to listen on buildStarted() of '%s' #%s".f(build.getFullName(), build.getBuildNumber()), t);
    }
  }


  @Override
  public void buildInterrupted(@NonNull SRunningBuild build) {
    val time = System.currentTimeMillis();
    try {
      Date started_at = build.getStartDate();
      Date finished_at = build.getFinishDate();
      if (finished_at == null) {
        finished_at = new Date();
      }
      String status = "error";
      if (build.getCanceledInfo() != null) {
        status = "cancelled";
      }

      val payload = gson.toJson(buildPayload(build, status, started_at, finished_at));
      gson.fromJson(payload, Map.class); // Sanity check of JSON generated
      log("Build '%s/#%s' interrupted, payload is '%s'".f(build.getFullName(), build.getBuildNumber(), payload));

      /////////////////////////////////////////////////
      // log("state: started");
      // log("Revision:");
      // String head = "";
      // val revisions = build.getRevisions();
      // if (revisions.isEmpty() == false) {
      //   head = revisions.get(0).getRevision();
      // }
      // else {
      //   // if above fails, fall back to getting version from vcs root
      //   val vcsRoots = build.getBuildType().getVcsRootInstanceEntries();
      //   if (! vcsRoots.isEmpty()) {
      //     val vcsRoot = vcsRoots.get(0).getVcsRoot();
      //     head = vcsRoot.getCurrentRevision().getVersion();
      //   }
      // }
      // log(head);
      // log("Containing changes:");
      // for (VcsModification rev : build.getChanges(SelectPrevBuildPolicy.SINCE_LAST_SUCCESSFULLY_FINISHED_BUILD, false)) {
      //   log(rev.getVersion());
      // }
      /////////////////////////////////////////////////

      if (build.isPersonal()) {
        log("Skipping post for personal build.");
      } else {
        for (val url : settings.getUrls(build.getProjectExternalId())){
          postPayload(url, payload);
        }
      }

      log("Operation finished in %s ms".f(System.currentTimeMillis() - time));
    }
    catch (Throwable t) {
      error("Failed to listen on buildInterrupted() of '%s' #%s".f(build.getFullName(), build.getBuildNumber()), t);
    }
  }

  @Override
  public void buildTypeAddedToQueue(@NonNull SQueuedBuild build) {
    val time = System.currentTimeMillis();
    try {
      BuildPromotion prom = build.getBuildPromotion();
      Scm scm = null;
      if (prom.getVcsRootEntries().isEmpty() == false) {
        VcsRootInstance root = prom.getVcsRootEntries().get(0).getVcsRoot(); // TODO
        String branch = prom.getBranch() != null ? prom.getBranch().getName() : null;
        // if the build promotion doesn't have the actual branch name, try to
        // get the vcs root's default branch
        if (branch == "<default>" || branch == null) {
          branch = root.getProperty("branch"); // returns null for svn
        }
        scm = Scm.builder().url(root.getProperty("url")).
                                branch(branch).
                                commit(null).
                                changes(null).build();
      }

      final PayloadBuild payloadBuild = PayloadBuild.builder().
        full_url("%s/viewQueued.html?itemId=%d".f(buildServer.getRootUrl(), prom.getId())).
        build_id(null).
        status("queued").
        started_at(null).
        finished_at(null).
        scm(scm).
        artifacts(null).
        parameters(null).
        build();

      WebhookPayload payloadFull = WebhookPayload.of(build.getBuildType().getFullName(),
                               // http://127.0.0.1:8080/viewType.html?buildTypeId=Echo_Build
                               "%s/viewType.html?buildTypeId=%s".f(buildServer.getRootUrl(),
                                                                   build.getBuildType().getExternalId()), // same as Build.getExternalId()
                               payloadBuild);

      val payload = gson.toJson(payloadFull);
      gson.fromJson(payload, Map.class); // Sanity check of JSON generated
      log("Build '%s' queued, payload is '%s'".f(build.getBuildType().getFullName(), payload));

      if (build.isPersonal()) {
        log("Skipping post for personal build.");
      } else {
        for (val url : settings.getUrls(build.getBuildType().getProjectExternalId())){ // same as Build.getProjectExternalId()
          postPayload(url, payload);
        }
      }

      log("QUEUED operation finished in %s ms".f(System.currentTimeMillis() - time));
    }
    catch (Throwable t) {
      error("Failed to listen on buildTypeAddedToQueue() of '%s'".f(build.getBuildType().getFullName()), t);
    }
  }


  @SuppressWarnings({"FeatureEnvy" , "ConstantConditions"})
  //@SneakyThrows(VcsException.class)
  private WebhookPayload buildPayload(@NonNull SBuild build, String status, Date started_at, Date finished_at){
    Scm scm      = null;

    val revisions = build.getRevisions();
    if (revisions.isEmpty() == false) {
      BuildRevision rev = revisions.get(0); // TODO: do something if more than one build rev

      VcsRoot root = rev.getRoot();

      String url = root.getProperty("url");
      String branch = rev.getRepositoryVersion().getVcsBranch();

      //////////////////////////
      // debug(root.getProperty("branch"));
      // debug(build.getBranch().getName());
      // debug(build.getBranch().getDisplayName());
      // debug(build.getBranch().getName());
      // for (Map.Entry<String, String> entry : root.getProperties().entrySet()) {
      //   debug(entry.getKey() + ": " + entry.getValue());
      // }
      //////////////////////////

      String head = rev.getRevision();
      debug(head);

      // fallback method for populating scm.branch
      // useful if "Unable to collect changes" occurs
      if (branch == null) {
        val promotionBranch = build.getBuildPromotion().getBranch();
        if (promotionBranch != null)
        {
          branch = promotionBranch.getName();
        }
        else if (root.getVcsName() == "svn")
        {
          log("Couldn't find a branch name, probably because this build uses svn");
        }

        // val vcsRoots = build.getBuildType().getVcsRootInstanceEntries();
        //
        // if (! vcsRoots.isEmpty()) {
        //   log(vcsRoots.get(0).getCheckoutRulesSpecification());
        //   log(vcsRoots.get(0).getCheckoutRules().getAsString());
        //   for (String s : vcsRoots.get(0).getCheckoutRules().getBody()) {
        //     log(s);
        //   }
        // }
      }

      // val changes = new ArrayList<String>();
      // if (branch != null && branch.startsWith("refs/pull/") == false) {
      //   for (VcsModification mod : build.getChanges(SelectPrevBuildPolicy.SINCE_LAST_SUCCESSFULLY_FINISHED_BUILD, false)) {
      //     changes.add(mod.getVersion());
      //   }
      // }

      scm = Scm.builder().url(url).
                          branch(branch).
                          commit(head).
                          changes(null).build();
    }

    //////////////////////////
    // debug("status...");
    // debug(build.getStatusDescriptor().getStatus().toString());
    // debug(build.getStatusDescriptor().getStatus().getText());
    // debug(build.getStatusDescriptor().getText());
    // debug(build.getBuildStatus().toString());
    // debug(build.getBuildStatus().getText());
    // for (val problem : build.getFailureReasons()) {
    //   debug(problem.toString());
    //   debug(problem.getType());
    // }
    //////////////////////////

    val parameters = new HashMap<String, String>();
    val date = build.getParametersProvider().get("env.BuildDate");
    if (date != null) {
      parameters.put("build_date", date);
    }

    //////////////////////////
    // for (Map.Entry<String, String> entry : build.getParametersProvider().getAll().entrySet()) {
    //   debug(entry.getKey() + ": " + entry.getValue());
    // }
    //////////////////////////

    final PayloadBuild payloadBuild = PayloadBuild.builder().
      // http://127.0.0.1:8080/viewLog.html?buildTypeId=Echo_Build&buildId=90
      full_url("%s/viewLog.html?buildTypeId=%s&buildId=%s".f(buildServer.getRootUrl(),
                                                             build.getBuildType().getExternalId(),
                                                             build.getBuildId())).
      build_id(build.getBuildNumber()).
      status(status).
      started_at(started_at).
      finished_at(finished_at).
      scm(scm).
      artifacts(artifacts(build)).
      parameters(parameters).
      build();

    return WebhookPayload.of(build.getFullName(),
                             // http://127.0.0.1:8080/viewType.html?buildTypeId=Echo_Build
                             "%s/viewType.html?buildTypeId=%s".f(buildServer.getRootUrl(),
                                                                 build.getBuildType().getExternalId()),
                             payloadBuild);
  }


  /**
   * POSTs payload to the URL specified
   */
  private void postPayload(@NonNull String url, @NonNull String payload){
    try {
      val request  = HttpRequest.post(url).body(payload).contentType("application/json").open();
      // http://jodd.org/doc/http.html#sockethttpconnection
      ((SocketHttpConnection) request.httpConnection()).getSocket().setSoTimeout(POST_TIMEOUT);
      val response = request.send();

      // allow all successful status codes
      int status_code = response.statusCode();
      if ( status_code >= 200 && status_code < 300) {
        log("Payload POST-ed to '%s'".f(url));
      }
      else {
        error("POST-ing payload to '%s' - got %s response: %s".f(url, response.statusCode(), response));
      }
    }
    catch (Throwable t) {
      error("Failed to POST payload to '%s'".f(url), t);
    }
  }


  /**
   * Retrieves map of build's artifacts (archived in TeamCity and uploaded to S3):
   * {'artifact.jar' => {'archive' => 'http://teamcity/artifact/url', 's3' => 'https://s3-artifact/url'}}
   *
   * https://devnet.jetbrains.com/message/5257486
   * https://confluence.jetbrains.com/display/TCD8/Patterns+For+Accessing+Build+Artifacts
   */
  @SuppressWarnings({"ConstantConditions", "CollectionDeclaredAsConcreteClass", "FeatureEnvy"})
  private Map<String,Map<String, String>> artifacts(@NonNull SBuild build){

    val buildArtifacts = buildArtifacts(build);
    if (buildArtifacts.isEmpty()) {
      return Collections.emptyMap();
    }

    val rootUrl   = buildServer.getRootUrl();
    @SuppressWarnings("TypeMayBeWeakened")
    val artifacts = new HashMap<String, Map<String, String>>();

    if (notEmpty(rootUrl)) {
      for (val artifact : buildArtifacts){
        val artifactName = artifact.getName();
        if (".teamcity".equals(artifactName) || isEmpty(artifactName)) { continue; }

        // http://127.0.0.1:8080/repository/download/Echo_Build/37/echo-service-0.0.1-SNAPSHOT.jar
        final String url = "%s/repository/download/%s/%s/%s".f(rootUrl,
                                                               build.getBuildType().getExternalId(),
                                                               build.getBuildNumber(),
                                                               artifactName);
        artifacts.put(artifactName, map("archive", url));
      }
    }

    return Collections.unmodifiableMap(addS3Artifacts(artifacts, build));
  }


  /**
   * Retrieves current build's artifacts.
   */
  @SuppressWarnings("ConstantConditions")
  private Collection<File> buildArtifacts(@NonNull SBuild build){
    val artifactsDirectory = build.getArtifactsDirectory();
    if ((artifactsDirectory == null) || (! artifactsDirectory.isDirectory())) {
      return Collections.emptyList();
    }

    try {
      artifactsGuard.lockReading(artifactsDirectory);
      File[] files = artifactsDirectory.listFiles();
      return (files == null ? Collections.<File>emptyList() : Arrays.asList(files));
    }
    finally {
      artifactsGuard.unlockReading(artifactsDirectory);
    }
  }


  /**
   * Updates map of build's artifacts with S3 URLs:
   * {'artifact.jar' => {'s3' => 'https://s3-artifact/url'}}
   */
  @SuppressWarnings("FeatureEnvy")
  private Map<String,Map<String, String>> addS3Artifacts(@NonNull Map<String, Map<String, String>> artifacts,
                                                         @NonNull @SuppressWarnings("TypeMayBeWeakened") SBuild build){

    val s3SettingsFile = new File(serverPaths.getConfigDir(), S3_SETTINGS_FILE);

    if (! s3SettingsFile.isFile()) {
      return artifacts;
    }

    val s3Settings   = readJsonFile(s3SettingsFile);
    val bucketName   = ((String) s3Settings.get("artifactBucket"));
    val awsAccessKey = ((String) s3Settings.get("awsAccessKey"));
    val awsSecretKey = ((String) s3Settings.get("awsSecretKey"));

    if (isEmpty(bucketName)) {
      return artifacts;
    }

    try {
      AmazonS3 s3Client = isEmpty(awsAccessKey, awsSecretKey) ?
        new AmazonS3Client() :
        new AmazonS3Client(new BasicAWSCredentials(awsAccessKey, awsSecretKey));

      if (! s3Client.doesBucketExist(bucketName)) {
        return artifacts;
      }

      // "Echo::Build/15/"
      final String prefix = "%s/%s/".f(build.getFullName().replace(" :: ", "::"), build.getBuildNumber());
      val objects = s3Client.listObjects(bucketName, prefix).getObjectSummaries();

      if (objects.isEmpty()) {
        return artifacts;
      }

      val region = s3Client.getBucketLocation(bucketName);

      for (val summary : objects){
        val artifactKey = summary.getKey();
        if (isEmpty(artifactKey) || artifactKey.endsWith("/build.json")) { continue; }

        final String artifactName = artifactKey.split("/").last();
        if (isEmpty(artifactName)) { continue; }

        // https://s3-eu-west-1.amazonaws.com/evgenyg-bakery/Echo%3A%3ABuild/45/echo-service-0.0.1-SNAPSHOT.jar
        final String url = "https://s3-%s.amazonaws.com/%s/%s".f(region, bucketName, artifactKey);

        if (artifacts.containsKey(artifactName)) {
          artifacts.get(artifactName).put("s3", url);
        }
        else {
          artifacts.put(artifactName, map("s3", url));
        }
      }
    }
    catch (Throwable t) {
      error("Failed to list objects in S3 bucket '%s'".f(bucketName), t);
    }

    return artifacts;
  }
}
