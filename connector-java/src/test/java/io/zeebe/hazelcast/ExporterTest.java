package io.zeebe.hazelcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.ITopic;
import io.zeebe.client.ZeebeClient;
import io.zeebe.exporter.proto.Schema;
import io.zeebe.hazelcast.connect.java.DeploymentEventListener;
import io.zeebe.hazelcast.connect.java.IncidentEventListener;
import io.zeebe.hazelcast.connect.java.JobEventListener;
import io.zeebe.hazelcast.connect.java.WorkflowInstanceEventListener;
import io.zeebe.hazelcast.exporter.ExporterConfiguration;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.test.ZeebeTestRule;
import io.zeebe.test.util.TestUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ExporterTest {

  private static final BpmnModelInstance WORKFLOW =
      Bpmn.createExecutableProcess("process")
          .startEvent("start")
          .sequenceFlowId("to-task")
          .serviceTask("task", s -> s.zeebeTaskType("test").zeebeInput("$.foo", "$.bar"))
          .sequenceFlowId("to-end")
          .endEvent("end")
          .done();

  private static final ExporterConfiguration CONFIGURATION = new ExporterConfiguration();

  @Rule
  public final ZeebeTestRule testRule = new ZeebeTestRule("zeebe.test.cfg.toml", Properties::new);

  private ZeebeClient client;
  private HazelcastInstance hz;

  @Before
  public void init() {
    client = testRule.getClient();

    final ClientConfig clientConfig = new ClientConfig();
    clientConfig.getNetworkConfig().addAddress("127.0.0.1:5701");
    hz = HazelcastClient.newHazelcastClient(clientConfig);
  }

  @After
  public void cleanUp() {
    hz.shutdown();
  }

  @Test
  public void shouldExportWorkflowInstanceEvents() {
    final List<Schema.WorkflowInstanceRecord> events = new ArrayList<>();

    final ITopic<byte[]> topic = hz.getTopic(CONFIGURATION.workflowInstanceTopic);
    topic.addMessageListener(new WorkflowInstanceEventListener(events::add));

    client
        .workflowClient()
        .newDeployCommand()
        .addWorkflowModel(WORKFLOW, "process.bpmn")
        .send()
        .join();

    client
        .workflowClient()
        .newCreateInstanceCommand()
        .bpmnProcessId("process")
        .latestVersion()
        .send()
        .join();

    TestUtil.waitUntil(() -> events.size() >= 4);

    assertThat(events)
        .extracting(r -> tuple(r.getElementId(), r.getMetadata().getIntent()))
        .containsSequence(
            tuple("process", "ELEMENT_READY"),
            tuple("process", "ELEMENT_ACTIVATED"),
            tuple("start", "START_EVENT_OCCURRED"),
            tuple("to-task", "SEQUENCE_FLOW_TAKEN"));
  }

  @Test
  public void shouldExportDeploymentEvents() {
    final List<Schema.DeploymentRecord> events = new ArrayList<>();

    final ITopic<byte[]> topic = hz.getTopic(CONFIGURATION.deploymentTopic);
    topic.addMessageListener(new DeploymentEventListener(events::add));

    client
        .workflowClient()
        .newDeployCommand()
        .addWorkflowModel(WORKFLOW, "process.bpmn")
        .send()
        .join();

    TestUtil.waitUntil(() -> events.size() >= 2);

    assertThat(events)
        .hasSize(2)
        .extracting(r -> r.getMetadata().getIntent())
        .containsExactly("CREATED", "DISTRIBUTED");
  }

  @Test
  public void shouldExportJobEvents() {
    final List<Schema.JobRecord> events = new ArrayList<>();

    final ITopic<byte[]> topic = hz.getTopic(CONFIGURATION.jobTopic);
    topic.addMessageListener(new JobEventListener(events::add));

    client
        .workflowClient()
        .newDeployCommand()
        .addWorkflowModel(WORKFLOW, "process.bpmn")
        .send()
        .join();

    client
        .workflowClient()
        .newCreateInstanceCommand()
        .bpmnProcessId("process")
        .latestVersion()
        .payload(Collections.singletonMap("foo", 123))
        .send()
        .join();

    TestUtil.waitUntil(() -> events.size() >= 1);

    assertThat(events).hasSize(1).extracting(r -> r.getMetadata().getIntent()).containsExactly("CREATED");
  }

  @Test
  public void shouldExportIncidentEvents() {
    final List<Schema.IncidentRecord> events = new ArrayList<>();

    final ITopic<byte[]> topic = hz.getTopic(CONFIGURATION.incidentTopic);
    topic.addMessageListener(new IncidentEventListener(events::add));

    client
        .workflowClient()
        .newDeployCommand()
        .addWorkflowModel(WORKFLOW, "process.bpmn")
        .send()
        .join();

    client
        .workflowClient()
        .newCreateInstanceCommand()
        .bpmnProcessId("process")
        .latestVersion()
        .send()
        .join();

    TestUtil.waitUntil(() -> events.size() >= 1);

    assertThat(events).hasSize(1).extracting(r -> r.getMetadata().getIntent()).containsExactly("CREATED");
  }
}