package org.apache.skywalking.apm.agent.core.k8s;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1PodList;
import io.kubernetes.client.models.V1ServiceList;
import io.kubernetes.client.util.Config;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;

/**
 * @author jian.tan
 */
public class KubernetesApiServiceTest {
    private static final ILog logger = LogManager.getLogger(KubernetesApiService.class);

    public static void main(String[] args) throws IOException, ApiException {
        ApiClient client = Config.defaultClient();
        Configuration.setDefaultApiClient(client);

        CoreV1Api api = new CoreV1Api();
        V1ServiceList serviceList = api.listNamespacedService("", true, null, null, null, null, null, null, null, null);

        org.apache.skywalking.apm.agent.core.conf.Config.Agent.SERVICE_NAME = serviceList.getItems().get(0).getMetadata().getName();

        V1PodList list = api.listPodForAllNamespaces(null, null, null, null, null, null, null, null, null);
        Iterator iterator = list.getItems().iterator();
        while (iterator.hasNext()) {
            logger.debug("pod list:" + iterator.next().toString());
        }

        Map<String, String> envMaps = System.getenv();
        Iterator iterator1 = envMaps.values().iterator();
        while (iterator1.hasNext()) {
            logger.debug("env:" + iterator.next().toString());
        }
    }
}
