/*
 * Copyright (c) 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package tv.bioscope.taskqueue;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.DataStoreFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.taskqueue.Taskqueue;
import com.google.api.services.taskqueue.TaskqueueRequest;
import com.google.api.services.taskqueue.TaskqueueRequestInitializer;
import com.google.api.services.taskqueue.TaskqueueScopes;
import com.google.api.services.taskqueue.model.Task;
import com.google.api.services.taskqueue.model.Tasks;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.Text;
import com.google.appengine.repackaged.com.google.gson.Gson;
import com.google.appengine.tools.remoteapi.RemoteApiInstaller;
import com.google.appengine.tools.remoteapi.RemoteApiOptions;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Sample which leases task from TaskQueueService, performs work on the payload of the task and then
 * deletes the task.
 *
 * @author Vibhooti Verma
 */
public class TaskQueueWorker {

  /**
   * Be sure to specify the name of your application. If the application name is {@code null} or
   * blank, the application will log a warning. Suggested format is "MyCompany-ProductName/1.0".
   */
    private static final String APPLICATION_NAME = "";
    private static final String REMOTE_API_URL = "bioscope-b2074.appspot.com";
    private static final long TASK_PROCESS_TIME_WINDOW_MS = 10000;

    // TODO : Avoid hardcoding creds. Move to resource file.
    private static final String CLIENT_ID = "622323392228-7jnk22dmh6capvcjmm1mhth7gcjrrdif.apps.googleusercontent.com";
    private static final String CLIENT_SECRET  = "VLFvCvnZzrmpCsbDD7F9gCI5";

    private static final int NUM_WORKER_THREADS = 1;

    private static String projectName = "s~bioscope-b2074";
    private static String taskQueueName = "create-stream-task-queue";
    private static int leaseSecs = 60;
    private static int numTasks = NUM_WORKER_THREADS;
    private static int FFSERVER_PORT = 8090;
    private static String INSTANCE_IP = "130.211.112.165";

    private static Executor executor = Executors.newFixedThreadPool(NUM_WORKER_THREADS);

    private static Set<Integer> availableFeedIds = new HashSet<>();
    private static Map<String, FeedInfo> streamIdToFeedInfoMap = new HashMap<>();

    static {
        availableFeedIds.add(1);
        availableFeedIds.add(2);
        availableFeedIds.add(3);
        availableFeedIds.add(4);
        availableFeedIds.add(5);
    }

    /** Directory to store user credentials. */
    private static final java.io.File DATA_STORE_DIR =
        new java.io.File(System.getProperty("user.home"), ".store/task_queue_sample");
  
    /**
    * Global instance of the {@link DataStoreFactory}. The best practice is to make it a single
    * globally shared instance across your application.
    */
    private static FileDataStoreFactory dataStoreFactory;

    /** Global instance of the HTTP transport. */
    private static HttpTransport httpTransport;

    /** Global instance of the JSON factory. */

    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

    private static final Gson gson = new Gson();

    private static DatastoreService dataStore;

    private static void initializeDatastore() {
        System.out.println("Initializing datastore");
        RemoteApiOptions options = new RemoteApiOptions()
                .server(REMOTE_API_URL, 443).useApplicationDefaultCredential();

        RemoteApiInstaller installer = new RemoteApiInstaller();
        try {
            installer.installOnAllThreads(options);
            dataStore = DatastoreServiceFactory.getDatastoreService();
        } catch (Exception e) {
            System.out.println("Failed to initialize DataStore");
            e.printStackTrace();
        }
    }

    /** Authorizes the installed application to access user's protected data. */
    private static Credential authorize() throws Exception {
        // load client secrets
        GoogleClientSecrets clientSecrets = new GoogleClientSecrets();
        GoogleClientSecrets.Details installed = new GoogleClientSecrets.Details();
        installed.setClientId(CLIENT_ID);
        installed.setClientSecret(CLIENT_SECRET);
        clientSecrets.setInstalled(installed);

        if (clientSecrets.getDetails().getClientId().startsWith("Enter")
            || clientSecrets.getDetails().getClientSecret().startsWith("Enter ")) {
        System.out.println("Enter Client ID and Secret from "
          + "https://code.google.com/apis/console/?api=taskqueue into "
          + "taskqueue-cmdline-sample/src/main/resources/client_secrets.json");
        System.exit(1);
        }
        // set up authorization code flow
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
            httpTransport, JSON_FACTORY, clientSecrets,
            Collections.singleton(TaskqueueScopes.TASKQUEUE)).setDataStoreFactory(
            dataStoreFactory).build();
        // authorize
        return new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");
    }

    /**
    * You can perform following operations using TaskQueueService:
    * <ul>
    * <li>leasetasks</li>
    * <li>gettask</li>
    * <li>deletetask</li>
    * <li>getqueue</li>
    * </ul>
    * <p>
    * For illustration purpose, we are first getting the stats of the specified queue followed by
    * leasing tasks and then deleting them. Users can change the flow according to their needs.
    * </p>
    */
    private static void run() throws Exception {


        httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        dataStoreFactory = new FileDataStoreFactory(DATA_STORE_DIR);
        // authorization
        Credential credential = authorize();

        initializeDatastore();

        // set up Taskqueue
        final Taskqueue taskQueue = new Taskqueue.Builder(
            httpTransport, JSON_FACTORY, credential).setApplicationName(APPLICATION_NAME)
            .setTaskqueueRequestInitializer(new TaskqueueRequestInitializer() {
          @Override
          public void initializeTaskqueueRequest(TaskqueueRequest<?> request) {
                request.setPrettyPrint(true);
              }
        }).build();

        // get queue
        com.google.api.services.taskqueue.model.TaskQueue queue = getQueue(taskQueue);
        System.out.println(queue);

        // Keep polling for tasks and process them as they come in.
        while (true) {
            // lease, execute and delete tasks
            Tasks tasks = getLeasedTasks(taskQueue);
            if (tasks == null || tasks.getItems() == null || tasks.getItems().size() == 0) {
                System.out.println("No tasks to lease. Sleeping..");
                Thread.sleep(1000);
                continue;
            }

            for (final Task leasedTask : tasks.getItems()) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            executeTask(leasedTask);
                            deleteTask(taskQueue, leasedTask);
                        } catch (Exception e) {
                            System.out.println("Failed to execute / delete task.. ");
                        }
                    }
                });
            }
        }
    }

    public static void main(String[] args) {

        try {
            run();
            // success!
            return;
        } catch (IOException e) {
            System.err.println(e.getMessage());
        } catch (Throwable t) {
            t.printStackTrace();
        }
        System.exit(1);
  }

  /**
   * Method that sends a get request to get the queue.
   *
   * @param taskQueue The task queue that should be used to get the queue from.
   * @return {@link com.google.api.services.taskqueue.model.TaskQueue}
   * @throws IOException if the request fails.
   */
  private static com.google.api.services.taskqueue.model.TaskQueue getQueue(Taskqueue taskQueue)
      throws IOException {
    Taskqueue.Taskqueues.Get request = taskQueue.taskqueues().get(projectName, taskQueueName);
    request.setGetStats(true);
    return request.execute();
  }

  /**
   * Method that sends a lease request to the specified task queue.
   *
   * @param taskQueue The task queue that should be used to lease tasks from.
   * @return {@link Tasks}
   * @throws IOException if the request fails.
   */
  private static Tasks getLeasedTasks(Taskqueue taskQueue) throws IOException {
    Taskqueue.Tasks.Lease leaseRequest =
        taskQueue.tasks().lease(projectName, taskQueueName, numTasks, leaseSecs);
      try {
          return leaseRequest.execute();
      } catch (Exception e) {
          System.out.println("Failed to lease tasks. Ignoring..");
          e.printStackTrace();
          return null;
      }
  }

    /**
    * This method actually performs the desired work on tasks. It can make use of payload of the
    * task. By default, we are just printing the payload of the leased task.
    *
    * @param task The task that should be executed.
    */
    private static void executeTask(Task task) throws UnsupportedEncodingException {
        // If task is too old, drop it
        System.out.println("Current time (ms) = " + System.currentTimeMillis());
        // Note : Task enqueue time is in microseconds in this implementation and
        // not seconds since epoch as the javadoc claims
        System.out.println("Task enqueue time (ms) = " + (task.getEnqueueTimestamp() / 1000));
        if ((System.currentTimeMillis() - (task.getEnqueueTimestamp() / 1000))
              >= TASK_PROCESS_TIME_WINDOW_MS) {
          System.out.println("Dropping old task..");
          return;
        }

        String taskPayload = new String(Base64.decodeBase64(task.getPayloadBase64().getBytes()), "UTF-8");

        EventStream eventStream = gson.fromJson(taskPayload, EventStream.class);

        //generateThumbnailAndWriteToDatastore(eventStream);

        if (eventStream.isLive) {
            createAlternateStreamAndWriteUrlToDatastore(eventStream);
        } else {
            destroyAlternateStream(eventStream);
        }
    }

    private static void createAlternateStreamAndWriteUrlToDatastore(final EventStream eventStream) {
        if (availableFeedIds.isEmpty()) {
            System.out.println("No available feeds to show stream!");
            return;
        }
        if (streamIdToFeedInfoMap.containsKey(eventStream.streamId)) {
            System.out.println("Already created alternate stream. Ignoring..");
            return;
        }
        try {
            System.out.println("Executing create alternate stream task..");
            int feedId = availableFeedIds.iterator().next();
            availableFeedIds.remove(feedId);

            String[] ffmpeg = new String[] {"ffmpeg", "-re",
                    "-i", decodeURL(eventStream.encodedUrl),
                    "http://127.0.0.1:" + FFSERVER_PORT + "/" + "feed" + feedId + ".ffm"};

            // TODO : Create background process
            Process p = Runtime.getRuntime().exec(ffmpeg);
            if (p.isAlive()) {
                System.out.println("ffmpeg process alive. ");
            } else {
                System.out.println("ffmpeg process died. exitValue = " + p.exitValue() + "args = " + Arrays.toString(ffmpeg));
            }

            FeedInfo feedInfo = new FeedInfo();
            feedInfo.feedId = feedId;
            feedInfo.streamingProcess = p;
            streamIdToFeedInfoMap.put(eventStream.streamId, feedInfo);

            String alternateStreamUrl = "http://" + INSTANCE_IP + ":" + FFSERVER_PORT + "/" + "test" + feedId + ".mpg";

            Key streamKey = KeyFactory.createKey("EventStream", eventStream.streamId);
            try {
                Entity stream = dataStore.get(streamKey);
                stream.setProperty("encodedAlternateUrl", encodeURL(alternateStreamUrl));
                stream.setProperty("lastAlternateUrlUpdatedTimeMs", System.currentTimeMillis());
                dataStore.put(stream);
                System.out.println("Successfully updated alternate URL for stream");
            } catch (EntityNotFoundException e) {
                // ignore (to be handled as part of input validation)
            }

        } catch (Exception e) {
            System.out.println("Failed to create stream! Cause = " +  e.getClass());
            e.printStackTrace();
        }
    }

    // TODO Figure out how to detect live more reliably
    private static void destroyAlternateStream(final EventStream eventStream) {
        if (!streamIdToFeedInfoMap.containsKey(eventStream.streamId)) {
            System.out.println("Process details unknown for stream destroy. Ignoring..");
            return;
        }
        try {
            System.out.println("Executing destroy alternate stream task..");
            FeedInfo feedInfo = streamIdToFeedInfoMap.get(eventStream.streamId);
            Process p = feedInfo.streamingProcess.destroyForcibly();
            p.waitFor();
            streamIdToFeedInfoMap.remove(eventStream.streamId);
            availableFeedIds.add(feedInfo.feedId);
        } catch (Exception e) {
            System.out.println("Failed to destroy stream! Cause = " +  e.getClass());
            e.printStackTrace();
        }
    }

    private static String decodeURL(final String encodedUrl) throws UnsupportedEncodingException {
        // Note : Uri.parse(encodedUrl) doesnt not work on all devices. Hence, use of URLDecoder.
        return URLDecoder.decode(encodedUrl, "UTF-8");
    }

    private static String encodeURL(final String url) throws UnsupportedEncodingException {
        // Note : Uri.parse(encodedUrl) doesnt not work on all devices. Hence, use of URLDecoder.
        return URLEncoder.encode(url, "UTF-8");
    }

    private static void generateThumbnailAndWriteToDatastore(final EventStream eventStream) {
        File file = null;
        try {
            System.out.println("Executing create thumbnail task..");
            file = new File("/tmp/" + eventStream.streamId + UUID.randomUUID() + ".jpg");
            String streamUrl = URLDecoder.decode(eventStream.encodedUrl, "UTF-8");
            System.out.println("Stream ID = " + eventStream.streamId);
            System.out.println("Stream URL = " + streamUrl);
            if (file.exists()) {
                file.delete();
            }
            String[] ffmpeg = new String[] {"ffmpeg", "-i", streamUrl,
                    "-ss", "00:00:00",
                    "-vf", "scale=iw/2:-1",
                    "-qscale:v", "15",
                    "-threads", "1",
                    "-vframes", "1",
                    file.getAbsolutePath()};
            Process p = Runtime.getRuntime().exec(ffmpeg);
            p.waitFor();
            if (p.exitValue() == 0 && file.exists()) {
                System.out.println("Thumbnail generation succesful!");
                String encodedImage =  new String(Base64.encodeBase64(FileUtils.readFileToByteArray(file)), "UTF-8");
                System.out.println("Encoded image size = " + encodedImage.length());

                Key streamKey = KeyFactory.createKey("EventStream", eventStream.streamId);
                try {
                    Entity stream = dataStore.get(streamKey);
                    stream.setProperty("encodedThumbnail", new Text(encodedImage));
                    stream.setProperty("lastThumbnailUpdatedTimeMs", System.currentTimeMillis());
                    dataStore.put(stream);
                    System.out.println("Successfully updated thumbnail for stream");
                } catch (EntityNotFoundException e) {
                    // ignore (to be handled as part of input validation)
                }

            }

        } catch (Exception e) {
            System.out.println("Failed to generate thumbnail! Cause = " +  e.getClass());
            e.printStackTrace();
        } finally {
            if (file != null && file.exists()) {
                file.delete();
            }
        }
    }

    private class EventStream {
        String streamId;
        String encodedUrl;
        boolean isLive;
    }

    static class FeedInfo {
        int feedId;
        Process streamingProcess;
    }

  /**
   * Method that sends a delete request for the specified task object to the taskqueue service.
   *
   * @param taskQueue The task queue the specified task lies in.
   * @param task The task that should be deleted.
   * @throws IOException if the request fails
   */
  private static void deleteTask(Taskqueue taskQueue, Task task) throws IOException {
    Taskqueue.Tasks.Delete request =
        taskQueue.tasks().delete(projectName, taskQueueName, task.getId());
    request.execute();
  }
}
