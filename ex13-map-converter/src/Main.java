import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import com.ximpleware.AutoPilot;
import com.ximpleware.NavException;
import com.ximpleware.VTDGen;
import com.ximpleware.VTDNav;
import com.ximpleware.XPathEvalException;
import com.ximpleware.XPathParseException;

/**
 * OpenStreetMap data converter.
 * 
 * @author Tobias Faller
 *
 */
public class Main {

	private static final List<String> HIGHWAY_TYPES = Arrays.asList(new String[] {
			"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "service",
			"motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
			"living_street", "track",
			"track",
			"rest_area", "services"
	});

	static class Node {
		public Node(float lat, float lon, long id) {
			this.lat = lat;
			this.lon = lon;
			this.id = id;
		}

		float lat;
		float lon;
		long id;
		long newId;
		boolean used;
	}

	static class Way {
		public Way(String... nodes) {
			this.nodes = nodes;
		}

		String maxSpeed;
		String[] nodes;
	}

	/**
	 * Usage: program input.xml output.graph output.node_mapping
	 *   input.xml: The exported xml data from openstreetmap.org
	 *   output.graph: The generated graph for exercise 13
	 *   output.node_mapping: The map file which maps each used
	 *       openstreetmap node id to onto the generated graph node id
	 *
	 * @param args Program arguments
	 */
	public static void main(String[] args) {
		Path path = Paths.get(args[0]);
		Path output = Paths.get(args[1]);
		Path mappingOutput = Paths.get(args[2]);

		try(OutputStream out = Files.newOutputStream(output);
				OutputStream mOut = Files.newOutputStream(mappingOutput);
				PrintStream bOut = new PrintStream(out);
				PrintStream mbOut = new PrintStream(mOut)) {
			VTDGen gen = new VTDGen();
			gen.parseFile(path.toString(), true);

			VTDNav nav = gen.getNav();

			AutoPilot pilot = new AutoPilot(nav);
			AutoPilot refPilot = new AutoPilot(nav);
			AutoPilot maxSpeedPilot = new AutoPilot();
			AutoPilot onewayPilot = new AutoPilot();

			Map<String, Node> nodes = new HashMap<>();
			List<Way> ways = new LinkedList<>();

			pilot.selectXPath("//node");
			while (pilot.evalXPath() != -1) {
				String id = nav.toRawString(nav.getAttrVal("id"));
				String lat = nav.toString(nav.getAttrVal("lat"));
				String lon = nav.toString(nav.getAttrVal("lon"));
				try {
					nodes.put(id, new Node(Float.parseFloat(lat), Float.parseFloat(lon), Long.parseLong(id)));
				} catch (NumberFormatException formatException) {
					formatException.printStackTrace(System.err);
				}
			}

			// See: http://wiki.openstreetmap.org/wiki/DE:Key:highway
			pilot.selectXPath("//way[tag[@k=\"highway\" and ("
					+ HIGHWAY_TYPES.stream().map(v -> String.format("@v=\"%s\"", v)).collect(Collectors.joining(" or "))
					+ ")]]"
				);

			long arcCount = 0;
			int wayCount = 0;
			int wayInfCount = 0;
			while (pilot.evalXPath() != -1) {
				VTDNav refNav = nav.cloneNav();
				refPilot.bind(refNav);
				refPilot.selectElement("nd");

				VTDNav maxSpeedNav = nav.cloneNav();
				VTDNav onewayNav = nav.cloneNav();
				maxSpeedPilot.bind(maxSpeedNav);
				maxSpeedPilot.selectXPath("tag[@k=\"maxspeed\"]");
				onewayPilot.bind(onewayNav);
				onewayPilot.selectXPath("tag[@k=\"oneway\"]");

				List<String> nodesRefs = new ArrayList<>();
				while (refPilot.iterate()) {
					String ref = nav.toNormalizedString(refNav.getAttrVal("ref"));
					if (!nodes.containsKey(ref))
						continue;

					nodesRefs.add(ref);
				}

				Way way = new Way(nodesRefs.toArray(new String[0]));
				if (way.nodes.length <= 1) {
					continue;
				}

				arcCount += nodesRefs.size();
				wayCount += way.nodes.length - 1;

				while (maxSpeedPilot.evalXPath() != -1) {
					way.maxSpeed = maxSpeedNav.toNormalizedString(maxSpeedNav.getAttrVal("v"));
				}
				if (way.maxSpeed == null) {
					wayInfCount++;
					way.maxSpeed = "150"; // Define a maximum speed
				}

				for (String node : way.nodes) {
					Node n = nodes.get(node);
					n.used |= true; 
				}
				ways.add(way);

				boolean oneway = false;
				while (onewayPilot.evalXPath() != -1) {
					if("yes".equalsIgnoreCase(onewayNav.toNormalizedString(onewayNav.getAttrVal("v")))) {
						oneway = true;
						break;
					}
				}

				// Add reverse edges for two-way path
				if (!oneway) {
					List<String> reverseNodeList = new ArrayList<>(Arrays.asList(way.nodes));
					Collections.reverse(reverseNodeList);

					Way reverse = new Way();
					reverse.maxSpeed = way.maxSpeed;
					reverse.nodes = reverseNodeList.toArray(new String[0]);
					ways.add(reverse);
					arcCount += nodesRefs.size();
					wayCount += reverse.nodes.length - 1;
				}
			}

			long activeNodes = nodes.entrySet().stream().filter(n -> n.getValue().used)
					.collect(Collectors.counting()); 
			System.out.println("Nodes: " + activeNodes);
			System.out.println("Ways: " + wayCount);
			System.out.println("Ways without max: " + wayInfCount);

			bOut.printf("%d\n", activeNodes);
			bOut.printf("%d\n", arcCount);

			long newId = 0;
			for (Entry<String, Node> nodeEntry : nodes.entrySet()) {
				Node node = nodeEntry.getValue();
				if (!node.used) {
					continue;
				}

				node.newId = newId++;
				bOut.printf("%d %s %s\n", node.newId, node.lat, node.lon);
				mbOut.printf("%d %d\n", node.id, node.newId);
			}

			for (Way way : ways) {
				String lastNode = null;
				for (int i = 0; i < way.nodes.length; i++) {
					if (i != 0) {
						Node start = nodes.get(lastNode);
						Node end = nodes.get(way.nodes[i]);

						int maxSpeed = 0;
						if (way.maxSpeed != null) {
							try {
								maxSpeed = Integer.parseInt(way.maxSpeed);
							} catch (NumberFormatException formatException) { }
						}
						bOut.print(String.format(Locale.ROOT, "%d %d %.1f %d\n",
								start.newId, end.newId,
								distance(start.lat, start.lon, end.lat, end.lon),
								maxSpeed));
					}

					lastNode = way.nodes[i];
				}
			}
		} catch (XPathParseException | XPathEvalException | NavException | IOException ioException) {
			ioException.printStackTrace(System.err);
		}

		System.out.println("Finished");
	}

	// See https://stackoverflow.com/questions/837872/
	//   calculate-distance-in-meters-when-you-know-longitude-and-latitude-in-java
	private static float distance(float lat1, float lng1, float lat2, float lng2) {
		double earthRadius = 6371000; // meters
		double dLat = Math.toRadians(lat2 - lat1);
		double dLng = Math.toRadians(lng2 - lng1);
		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
				* Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
		double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

		return (float) (earthRadius * c);
	}
}
