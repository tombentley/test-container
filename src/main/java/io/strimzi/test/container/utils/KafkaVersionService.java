/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.test.container.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.JsonNode;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;
import org.testcontainers.shaded.org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *  A service for querying for Kafka versions using abstract criteria such as "latest version",
 *  the result of which may change over time (and thus, non-deterministically).
 */
public class KafkaVersionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaVersionService.class);

    private static final Pattern STRIMZI_TEST_CONTAINER_IMAGE_WITHOUT_KAFKA_VERSION = Pattern.compile("^test-container:(\\d+\\.\\d+\\.\\d+|latest)-kafka-.*$");
    private static final String KAFKA_VERSIONS_URL_JSON = "https://raw.githubusercontent.com/strimzi/test-container-images/main/kafka_versions.json";
    private static final String STRIMZI_BASE_IMAGE = "quay.io/strimzi-test-container/test-container";
    private static final String IMAGE_FORMAT = "%s:%s-kafka-%s";

    private static class InstanceHolder {
        public static final KafkaVersionService INSTANCE = new KafkaVersionService();
    }

    private String jsonVersion;
    private final List<KafkaVersion> logicalKafkaVersionEntities = new ArrayList<>();

    /**
     * Constructor of the KafkaVersionService, which invokes resolution and parsing phase. In resolution, we fetch
     * data from @code{KAFKA_VERSIONS_URL_JSON} and then we parse that json scheme, which gives us list of @code{KafkaVersion}
     * objects.
     */
    public KafkaVersionService() {
        // scrape json schema and fill the inner list of versions
        this.resolveAndParse();
    }

    /**
     * Get singleton instance lazily
     *
     * @return singleton instance
     */
    public static KafkaVersionService getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Get complete image name from given Kafka and Strimzi image version
     * If any of the given versions is {@code null} this method fetches the latest release versions using
     * {@link KafkaVersionService}.
     *
     * @param strimziBaseImage Strimzi base image name
     * @param strimziTestContainerImageVersion Strimzi test container image version
     * @param kafkaVersion Kafka version
     * @return complete image name
     */
    public static String strimziTestContainerImageName(String strimziBaseImage, String strimziTestContainerImageVersion, String kafkaVersion) {
        if (strimziBaseImage == null || strimziBaseImage.isEmpty()) {
            strimziBaseImage = STRIMZI_BASE_IMAGE;
        }
        if (strimziTestContainerImageVersion == null || strimziTestContainerImageVersion.isEmpty()) {
            strimziTestContainerImageVersion = KafkaVersionService.getInstance().latestRelease().getStrimziTestContainerVersion();
            LOGGER.info("No Strimzi test container version specified. Using latest release:{}", strimziTestContainerImageVersion);
        }

        if (kafkaVersion == null || kafkaVersion.isEmpty()) {
            kafkaVersion = KafkaVersionService.getInstance().latestRelease().getVersion();
            LOGGER.info("No Kafka version specified. Using latest release:{}", kafkaVersion);
        }
        return String.format(IMAGE_FORMAT, strimziBaseImage, strimziTestContainerImageVersion, kafkaVersion);
    }

    /**
     * Represents a concrete "Kafka version"
     */
    public static class KafkaVersion implements Comparable<KafkaVersion> {
        private final String version;
        private final String image;

        /**
         * Kafka version constructor
         * @param version kafka version
         * @param image image of the strimzi test container
         */
        public KafkaVersion(String version, String image) {
            this.version = version;
            this.image = image;
        }

        @Override
        public int compareTo(KafkaVersion o) {
            return compareVersions(this.version, o.version);
        }

        /**
         * Compare two decimal version strings, e.g. 1.10.1 &gt; 1.9.2
         * @param version1 The first version.
         * @param version2 The second version.
         * @return 0 if version1 == version2;
         * -1 if version1 &lt; version2;
         * 1 if version1 &gt; version2.
         */
        public static int compareVersions(String version1, String version2) {
            String[] components = version1.split("\\.");
            String[] otherComponents = version2.split("\\.");
            for (int i = 0; i < Math.min(components.length, otherComponents.length); i++) {
                int x = Integer.parseInt(components[i]);
                int y = Integer.parseInt(otherComponents[i]);
                // equality of version is ignored...
                if (x < y) {
                    return -1;
                } else if (x > y) {
                    return 1;
                }

            }
            return components.length - otherComponents.length;
        }

        /**
         * Get the Kafka version in the following format (i.e., "3.0.0")
         *
         * @return kafka version
         */
        public String getVersion() {
            return version;
        }

        /**
         * Get the image in the following format (i.e., "test-container:0.1.0-kafka-3.0.0")
         *
         * @return image
         */
        public String getImage() {
            return image;
        }

        /**
         * Auxiliary method, which fetch from @code{image} by using regex @code{STRIMZI_TEST_CONTAINER_IMAGE_WITHOUT_KAFKA_VERSION}
         * the Strimzi test container version.
         *
         * @return strimzi test container version
         */
        public String getStrimziTestContainerVersion() {
            Matcher regex = STRIMZI_TEST_CONTAINER_IMAGE_WITHOUT_KAFKA_VERSION.matcher(this.image);

            // only one occurrence in the string
            if (regex.find()) {
                final String strimziContainerVersion = regex.group(1);
                LOGGER.info("Find the version of Strimzi kafka container:{}", strimziContainerVersion);
                return strimziContainerVersion;
            } else {
                throw new RuntimeException("Unable to find version of Strimzi kafka container from image name - " + this.image);
            }
        }

        @Override
        public String toString() {
            return "LogicalKafkaVersionEntity{" +
                "version='" + this.version + '\'' +
                ", image='" + this.image + '\'' +
                '}';
        }
    }

    /**
     * Get the latest release where the result is intentionally not deterministic.
     * It's the released test-container-images image with the highest Kafka version number.
     * The result will change when a new version of Kafka is released with a higher Kafka version number,
     * and a test-container-images image which contains that Kafka version has been built and released.
     * @return LogicalKafkaVersion latest release
     */
    public KafkaVersion latestRelease() {
        // at least one release in the json schema is needed
        if (this.logicalKafkaVersionEntities == null || this.logicalKafkaVersionEntities.size() < 1) {
            throw new IllegalStateException("Wrong json schema! It must have at least one release");
        }

        KafkaVersion latestRelease = this.logicalKafkaVersionEntities.get(0);

        for (int i = 1; i < this.logicalKafkaVersionEntities.size(); i++) {
            if (latestRelease.compareTo(this.logicalKafkaVersionEntities.get(i)) < 0) {
                latestRelease = this.logicalKafkaVersionEntities.get(i);
            }
        }

        LOGGER.info("Latest release of Kafka is:{}", latestRelease.toString());

        return latestRelease;
    }

    /**
     * Gets the previous minor release.
     * If this instance were 2.8.1 then at a certain point in time this method
     * might return a version reflecting 2.7.2.
     * On a later date it might return 2.7.3.
     * It is allowed to cross major version boundaries.
     * E.g. the previous minor release to 3.0.0 might be 2.8.2
     * ============================================================
     * | (prev prev minor) ---  (previous minor) ---  (current) |
     *                                                     |
     *                                                     *
     * |    2.8.1   &lt;---&gt;     2.8.2  &lt;---&gt;   3.0.0  |
     * ============================================================
     * Assuming that test container images `kafka_versions.yaml` has following content:
     * {
     *   "version": 1,
     *   "kafkaVersions": {
     *     "2.8.1": "test-container:latest-kafka-2.8.1",
     *     "2.8.2": "test-container:latest-kafka-2.8.2",
     *     "3.0.0": "test-container:latest-kafka-3.0.0"
     *   }
     * }
     * @return LogicalKafkaVersion the previous minor release
     */
    public KafkaVersion previousMinor() {
        if (this.logicalKafkaVersionEntities == null || this.logicalKafkaVersionEntities.size() < 1) {
            throw new IllegalStateException("Wrong json schema! It must have at least one release");
        }

        // sort in this case always order the latest release at the end so the previous one is the previous minor release
        this.logicalKafkaVersionEntities.sort(KafkaVersion::compareTo);

        final KafkaVersion previousMinorRelease = this.logicalKafkaVersionEntities.get(this.logicalKafkaVersionEntities.size() - 2);
        LOGGER.info("Previous minor release of Kafka is:{}", previousMinorRelease);
        return previousMinorRelease;
    }

    /**
     * Resolve the logical version to an actual version
     */
    private void resolveAndParse() {
        // Connect to the URL using java's native library
        try {
            StringWriter writer = new StringWriter();
            IOUtils.copy(new URL(KAFKA_VERSIONS_URL_JSON).openStream(), writer, StandardCharsets.UTF_8);
            String pureStringJson = writer.toString().replaceAll("(?m)^#.*(?:\\r?\\n)", "");
            JsonNode rootNode = new ObjectMapper().readValue(pureStringJson, JsonNode.class);

            this.jsonVersion = rootNode.get("version").toString();

            for (Iterator<Map.Entry<String, JsonNode>> iter = rootNode.get("kafkaVersions").fields(); iter.hasNext(); ) {
                Map.Entry<String, JsonNode> fields = iter.next();
                KafkaVersion logicalKafkaVersion = new KafkaVersion(fields.getKey(), fields.getValue().asText());
                this.logicalKafkaVersionEntities.add(logicalKafkaVersion);
            }
        } catch (IOException e) {
            LOGGER.error("Error occurred during instantiation of JsonReader!", e);
        }
    }

    @Override
    public String toString() {
        return "LogicalKafkaVersion{" +
            "jsonVersion='" + jsonVersion + '\'' +
            ", logicalKafkaVersionEntities=" + logicalKafkaVersionEntities.toString() +
            '}';
    }
}
