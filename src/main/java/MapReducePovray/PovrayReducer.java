package MapReducePovray;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapreduce.Reducer;

import com.google.common.io.Files;

/**
 * Reduce implementation to combine multiple images to a GIF-animation using Graphics Magick.
 * 
 * Runs only on Linux as this implementation uses a precompiled binary to do the image conversion.
 * 
 * @author Sebastian Sams
 *
 */
public class PovrayReducer extends Reducer<IntWritable, FrameWriteable, IntWritable, FrameWriteable> {

	private static File GM_BINARY;
	
	// static constructor to extract the binary before the class is used
	static {
		final File workingDir = Files.createTempDir();
		workingDir.deleteOnExit();
		
		try {
			GM_BINARY = extractGraphicsMagick(workingDir);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	@Override
	public void reduce(IntWritable key, Iterable<FrameWriteable> values, Context context) throws IOException, InterruptedException {
		// prepare temporary directory and process arguments
		final File workingDir = Files.createTempDir();
		workingDir.deleteOnExit();
		final List<String> commandArray = new ArrayList<>(Arrays.asList(GM_BINARY.getAbsolutePath(), "convert", "-loop", "0", "-delay", "0"));
		// TODO: get first frame number, but rearrange iterator
		//final int firstFrameNumber = values.iterator().next().getFrameNumber();
		
		// write individual frames to disk and collect filenames
		int frameCount = 0;
		for (final FrameWriteable frame : values) {
			frameCount++;
			commandArray.add(frame.saveImage(workingDir));
		}
		if (frameCount == 0) {
			System.out.println("reducer: nothing to do (no values) for key " + key);
			return;
		}
		
		final String outputFileName = "output.gif";
		commandArray.add(outputFileName);
		
		// run gm
		final ProcessBuilder processBuilder = new ProcessBuilder(commandArray)
				.directory(workingDir)
				// redirect input/output of process to stdout/stderr of current java process
				// allows viewing messages from gm directly in the console
				.redirectError(Redirect.INHERIT)
				.redirectOutput(Redirect.INHERIT);
		final Process process = processBuilder.start();
		final int returnCode = process.waitFor();
		if (returnCode != 0) {
			throw new IOException("gm process terminated with exit code " + returnCode);
		}
		
		// read the generated output and pass it to Hadoop
		context.write(key, new FrameWriteable(1, new File(workingDir, outputFileName)));
		
		// don't check for errors as it's a temporary directory, so it's deleted by the OS at some point anyway
		FileUtils.deleteQuietly(workingDir);
	}
	
	/**
	 * Extract the  Graphics Magick binary from the JAR archive. The binary is executable after extraction.
	 * @param directory the directory where to store the extracted binary
	 * @throws IOException if an I/O error occurs
	 */
	private static File extractGraphicsMagick(File directory) throws IOException {
		final URL gmURL = PovrayReducer.class.getResource("../gm");
		if (gmURL == null) {
			throw new IOException("could not determine source location of gm binary");
		}
		
		final File outputFile = new File(directory, "gm");
		FileUtils.copyURLToFile(gmURL, outputFile);
		outputFile.setExecutable(true);
		return outputFile;
	}
}
