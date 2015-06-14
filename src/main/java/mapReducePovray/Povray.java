package mapReducePovray;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

/*
 * Runs MapReduce Povray workflow on Hadoop
 * 
 * @author Jan Schlenker
 * 
 */
public class Povray {
	public static String INVALID_SYNTAX = "Invalid number of parameters.\n"
			+ "Usage: $HADDOP_HOME/bin/hadoop jar target/map-reduce-povray-0.0.1-SNAPSHOT.jar MapReducePovray.Povray <input-dir> <output-dir>";
	
	public static void main(String[] args) throws Exception {
		if(args.length != 2) {
			System.err.println(INVALID_SYNTAX);
			System.exit(1);
		}
		
		Configuration conf = new Configuration();
		Job job = Job.getInstance(conf, "povray");
		job.setJarByClass(Povray.class);
		job.setMapperClass(PovrayMapper.class);
		job.setCombinerClass(PovrayReducer.class);
		job.setReducerClass(PovrayReducer.class);
		job.setOutputKeyClass(IntWritable.class);
		job.setOutputValueClass(FrameWriteable.class);
		FileInputFormat.addInputPath(job, new Path(args[0]));
		FileOutputFormat.setOutputPath(job, new Path(args[1]));
		System.exit(job.waitForCompletion(true) ? 0 : 1);
	}
}