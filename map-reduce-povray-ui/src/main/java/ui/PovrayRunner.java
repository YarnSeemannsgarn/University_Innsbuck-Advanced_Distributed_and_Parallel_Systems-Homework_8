package ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.regions.Region;
import com.amazonaws.services.elasticmapreduce.AmazonElasticMapReduce;
import com.amazonaws.services.elasticmapreduce.AmazonElasticMapReduceClient;
import com.amazonaws.services.elasticmapreduce.model.ActionOnFailure;
import com.amazonaws.services.elasticmapreduce.model.AddJobFlowStepsRequest;
import com.amazonaws.services.elasticmapreduce.model.AddJobFlowStepsResult;
import com.amazonaws.services.elasticmapreduce.model.DescribeStepRequest;
import com.amazonaws.services.elasticmapreduce.model.HadoopJarStepConfig;
import com.amazonaws.services.elasticmapreduce.model.Step;
import com.amazonaws.services.elasticmapreduce.model.StepConfig;
import com.amazonaws.services.elasticmapreduce.model.StepState;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3ObjectSummary;

/**
 * Renders a Povray animation using a running Amazon EMR cluster.
 * @author Sebastian Sams
 *
 */
public class PovrayRunner {
	
	/**
	 * Name of the jar file containing the Map-Reduce implementation for Povray.
	 * Must be present in the S3 bucket used for input/output.
	 */
	private static final String JAR_NAME = "map-reduce-povray-1.0.jar";
	
	/**
	 * Name of the main class in the jar-file.
	 */
	private static final String JAR_MAIN_CLASS = "mapReducePovray.Povray";

	/**
	 * Number of frames which should be rendered by each mapper.
	 */
	private static final int FRAMES_PER_MAPPER = 10;
	
	/**
	 * Name of the Povray scene description when uploaded to S3.
	 */
	private static final String POV_FILE_NAME = "render.pov";
	
	private final AmazonS3 mS3Client;
	private final AmazonElasticMapReduce mMapReduceClient;
	
	private final String mClusterId;
	private final String mStorageBucket;
	
	private final List<ProgressListener> mProgressListeners;
	
	/**
	 * Create a new remote renderer.
	 * @param credentials Amazon AWS credentials used for accessing the cluster
	 * @param region AWS region where the cluster and S3 buckets are located
	 * @param clusterId the ID of the cluster (job flow) which should be used for rendering
	 * @param storageBucket the S3 bucket to use for storing input/output files
	 */
	public PovrayRunner(AWSCredentials credentials, Region region, String clusterId, String storageBucket) {
		mClusterId = clusterId;
		mStorageBucket = storageBucket;
		mProgressListeners = new LinkedList<>();
		
		mS3Client = new AmazonS3Client(credentials);
		mS3Client.setRegion(region);
		
		mMapReduceClient = new AmazonElasticMapReduceClient(credentials);
		mMapReduceClient.setRegion(region);
	}
	
	/**
	 * Render the animation.
	 * @param povFile Povray scene description which should be rendered
	 * @param frames number of frames to render
	 * @param output local file for storing the generated animation
	 * @throws IOException if an error occurs
	 */
	public void render(File povFile, int frames, File output) throws IOException {
		final String s3BaseUrl = "s3://" + mStorageBucket + "/";
		
		try {
			// cleanup old files
			notifyProgressMessageChanged("cleaning up remote storage");
			deleteS3directory("input");
			deleteS3directory("output");
			
			// prepare input files for the job
			notifyProgressMessageChanged("preparing required input files");
			prepareInput(frames);
			mS3Client.putObject(mStorageBucket, POV_FILE_NAME, povFile);
			
			// create and run the job
			notifyProgressMessageChanged("starting rendering job");
			HadoopJarStepConfig jarStepConfig = new HadoopJarStepConfig()
			    .withJar(s3BaseUrl + JAR_NAME)
			    .withMainClass(JAR_MAIN_CLASS)
			    .withArgs(s3BaseUrl + "input/", s3BaseUrl + "output/", s3BaseUrl + POV_FILE_NAME);
			final AddJobFlowStepsResult stepResult = mMapReduceClient
					.addJobFlowSteps(new AddJobFlowStepsRequest(mClusterId)
							.withSteps(new StepConfig("render " + frames + " '" + povFile.getName() + "'", jarStepConfig)
							.withActionOnFailure(ActionOnFailure.CONTINUE)));
			
			notifyProgressMessageChanged("waiting for completion of rendering job");
			if (waitForJobCompletion(stepResult.getStepIds().get(0), 2, TimeUnit.SECONDS)) {	
				// download the generated gif
				notifyProgressMessageChanged("downloading generated animation");
				downloadResult(output);
			} else {
				throw new IOException("job execution failed");
			}
		} catch (AmazonClientException e) {
			throw new IOException("error when communicating with Amazon AWS:\n" + e.getMessage() + "", e);
		}
		
		notifyProgressMessageChanged("rendering complete");
	}
	
	/**
	 * Wait until the job execution is completed.
	 * @param stepId id of the step
	 * @param checkInterval time to wait between periodic checks for completion
	 * @param unit time unit of the specified interval
	 * @return <code>true</code> if the job has been completed successfully, <code>false</code> if it failed
	 */
	private boolean waitForJobCompletion(String stepId, int checkInterval, TimeUnit unit) {
		try {
			while (true) {
				final Step step = mMapReduceClient.describeStep(new DescribeStepRequest().withClusterId(mClusterId).withStepId(stepId)).getStep();
				switch (StepState.fromValue(step.getStatus().getState())) {
				case COMPLETED:
					return true;
					
				case CANCELLED:
				case FAILED:
				case INTERRUPTED:
					return false;
					
				case PENDING:
				case RUNNING:
				default:
					Thread.sleep(unit.toMillis(checkInterval));
					break;
				}
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Delete a "directory" in S3. A directory is equivalent to all files starting with a common prefix and are delimited by a slash.
	 * @param directory the directory to delete (without the slash at the end)
	 */
	private void deleteS3directory(String directory) {
		// delete contents
		final List<S3ObjectSummary> objects = mS3Client.listObjects(mStorageBucket, directory + "/").getObjectSummaries();
		if (objects.size() > 0) {
			final List<String> keys = new ArrayList<String>(objects.size());
			for (final S3ObjectSummary object : objects) {
				keys.add(object.getKey());
			}
			mS3Client.deleteObjects(new DeleteObjectsRequest(mStorageBucket).withKeys(keys.toArray(new String[0])));
		}
		
		// delete directory itself
		mS3Client.deleteObject(mStorageBucket, directory);
	}

	/**
	 * Prepare the input files describing the frames to render as 
	 * required by the Map-Reduce implementation.
	 * Creates the required files and uploads them to the S3 bucket.
	 * @param frames the number of frames to render
	 * @throws IOException if an I/O error occurs
	 */
	private void prepareInput(int frames) throws IOException {
		final Path tempDir = Files.createTempDirectory(null);
		tempDir.toFile().deleteOnExit();
		
		try {
			int start;
			int end = 0;
			int inputNumber = 0;
			do {
				start = end + 1;
				end = Math.min(start + FRAMES_PER_MAPPER - 1, frames);
				
				final Path file = tempDir.resolve("file" + inputNumber);
				Files.write(file, Arrays.asList("1 " + frames + " " + start + " " + end), Charset.forName("UTF-8"));
				mS3Client.putObject(mStorageBucket, "input/file" + inputNumber, file.toFile());
				inputNumber++;
			} while (end != frames);
		} catch (IOException e) {
			throw new IOException("could not create input files for job", e);
		}
	}
	
	/**
	 * Download the generated animation.
	 * @param output the local file for storing the result
	 */
	private void downloadResult(File output) {
		mS3Client.getObject(new GetObjectRequest(mStorageBucket, "output/result"), output);
	}
	
	/**
	 * Notify all registered listeners about a new progress message.
	 * @param message the new message
	 */
	protected void notifyProgressMessageChanged(String message) {
		for (final ProgressListener listener : mProgressListeners) {
			listener.progressMessageChanged(message);
		}
	}
	
	/**
	 * Add a listener to receive messages about the current progress.
	 * @param listener the listener to add
	 */
	public void addProgressListener(ProgressListener listener) {
		mProgressListeners.add(listener);
	}
	
	/**
	 * Remove a listener and stop receiving progress updates. This method has no effect if
	 * the specified listener is currently not registered. 
	 * @param listener the listener to remove
	 */
	public void removeProgressListener(ProgressListener listener) {
		mProgressListeners.remove(listener);
	}

}
