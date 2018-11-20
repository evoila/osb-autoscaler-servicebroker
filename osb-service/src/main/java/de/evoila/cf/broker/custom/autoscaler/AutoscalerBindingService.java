package de.evoila.cf.broker.custom.autoscaler;

import de.evoila.cf.autoscaler.api.binding.Binding;
import de.evoila.cf.autoscaler.api.binding.BindingContext;
import de.evoila.cf.broker.bean.AutoscalerBean;
import de.evoila.cf.broker.bean.EndpointConfiguration;
import de.evoila.cf.broker.exception.ServiceBrokerException;
import de.evoila.cf.broker.exception.ServiceInstanceBindingBadRequestException;
import de.evoila.cf.broker.exception.ServiceInstanceBindingException;
import de.evoila.cf.broker.model.RouteBinding;
import de.evoila.cf.broker.model.ServiceInstance;
import de.evoila.cf.broker.model.ServiceInstanceBinding;
import de.evoila.cf.broker.model.ServiceInstanceBindingRequest;
import de.evoila.cf.broker.model.catalog.ServerAddress;
import de.evoila.cf.broker.model.catalog.plan.Plan;
import de.evoila.cf.broker.repository.*;
import de.evoila.cf.broker.service.AsyncBindingService;
import de.evoila.cf.broker.service.HAProxyService;
import de.evoila.cf.broker.service.impl.BindingServiceImpl;
import de.evoila.cf.security.utils.AcceptSelfSignedClientHttpRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by reneschollmeyer, evoila on 22.11.17.
 */
@Service
public class AutoscalerBindingService extends BindingServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(AutoscalerBindingService.class);

    private HttpHeaders headers = new HttpHeaders();

    private static final String CONTENT_TYPE = "Content-Type";

    private static final String APPLICATION_JSON = "application/json";

    private static final String BINDING_ENDPOINT = "/bindings";

    private static final String AS_CORE_IDENTIFIER = "osb-autoscaler-core";
    
    private RestTemplate restTemplate = new RestTemplate();

    private AutoscalerBean autoscalerBean;

    private EndpointConfiguration endpointConfiguration;

    private String endpoint;

    @PostConstruct
    private void init() {
        restTemplate = new RestTemplate();
        headers.add(CONTENT_TYPE, APPLICATION_JSON);
    }

    @ConditionalOnBean(AcceptSelfSignedClientHttpRequestFactory.class)
    @Autowired(required = false)
    private void selfSignedRestTemplate(AcceptSelfSignedClientHttpRequestFactory requestFactory) {
        restTemplate.setRequestFactory(requestFactory);
    }

    public AutoscalerBindingService(BindingRepository bindingRepository, ServiceDefinitionRepository serviceDefinitionRepository,
                                    ServiceInstanceRepository serviceInstanceRepository,
                                    RouteBindingRepository routeBindingRepository,
                                    HAProxyService haProxyService, AutoscalerBean autoscalerBean,
                                    EndpointConfiguration endpointConfiguration, JobRepository jobRepository,
                                    AsyncBindingService asyncBindingService, PlatformRepository platformRepository) {
        super(bindingRepository, serviceDefinitionRepository, serviceInstanceRepository, routeBindingRepository,
                haProxyService, jobRepository, asyncBindingService, platformRepository);
        this.endpointConfiguration = endpointConfiguration;
        this.autoscalerBean = autoscalerBean;
        endpointConfiguration.getCustom().forEach(server -> {
            if (server.getIdentifier().equals(AS_CORE_IDENTIFIER))
                this.endpoint = server.getUrl();
        });
    }

    @Override
    protected RouteBinding bindRoute(ServiceInstance serviceInstance, String route) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected ServiceInstanceBinding bindService(String bindingId, ServiceInstanceBindingRequest serviceInstanceBindingRequest,
                                                 ServiceInstance serviceInstance, Plan plan) throws ServiceBrokerException {

        if (this.endpoint == null)
            throw new ServiceBrokerException("Autoscaler Core could not be found in custom endpoints with identifier: " + AS_CORE_IDENTIFIER);

        ResponseEntity<String> response = post(bindingId, serviceInstanceBindingRequest.getAppGuid(), serviceInstance);

        if (!response.getStatusCode().is2xxSuccessful()) {
            log.error(new ServiceInstanceBindingException(serviceInstance.getId(), bindingId, response.getStatusCode(),
                    response.getBody()).getMessage());

            if (response.getStatusCode() == HttpStatus.BAD_REQUEST) {
                throw new ServiceInstanceBindingBadRequestException(bindingId, response.getBody());
            }
            if (response.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new ServiceBrokerException("The Broker is not authorized at the service instance. (401 Response)");
            }
            if (response.getStatusCode() == HttpStatus.CONFLICT) {
                throw new ServiceBrokerException("The service instance already holds a binding in conflict with the requested one. Maybe the broker and the service instance are out of sync.");
            }
            throw new ServiceBrokerException("The Broker faced an unexpected error while calling the ServiceInstance to create a binding: " + response.getStatusCodeValue() + " - " + response.getBody());
        } else {
            log.info("Binding resulted in " + response.getStatusCode() + ", serviceInstance = "
                    + serviceInstance.getId() + ", bindingId = " + bindingId);

        }

        ServiceInstanceBinding serviceInstanceBinding = new ServiceInstanceBinding(bindingId, serviceInstance.getId(), null, null);
        serviceInstanceBinding.setAppGuid(serviceInstanceBindingRequest.getAppGuid());
        return serviceInstanceBinding;
    }

    @Override
    protected Map<String, Object> createCredentials(String bindingId, ServiceInstanceBindingRequest serviceInstanceBindingRequest,
                                                    ServiceInstance serviceInstance, Plan plan, ServerAddress serverAddress) {
        Map<String, Object> credentials = new HashMap<>();

        return credentials;
    }

    @Override
    protected void unbindService(ServiceInstanceBinding binding, ServiceInstance serviceInstance, Plan plan) throws ServiceBrokerException {
        String bindingId = binding.getId();
        ResponseEntity<String> response = delete(bindingId, serviceInstance.getId());

        if (!response.getStatusCode().is2xxSuccessful()) {
            log.error(new ServiceInstanceBindingException(serviceInstance.getId(), bindingId, response.getStatusCode(),
                    response.getBody()).getMessage());

            if (response.getStatusCode() == HttpStatus.GONE) {
                throw new ServiceBrokerException("ServiceInstance can not find the binding. Maybe the service broker and the ServiceInstance are out of sync. (410 Response)");
            }
            if (response.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new ServiceBrokerException("The Broker is not authorized at the service instance. (401 Response)");
            }
            throw new ServiceBrokerException("The Broker faced an unexpected error while calling the ServiceInstance to delete a binding: " + response.getStatusCodeValue() + " - " + response.getBody());
        } else {
            log.info("Unbinding resulted in " + response.getStatusCode() + ", serviceInstance = "
                    + serviceInstance.getId() + ", bindingId = " + bindingId);
        }
    }

    private ResponseEntity<String> post(String bindingId, String appGuid, ServiceInstance serviceInstance) {
        BindingContext context = new BindingContext(autoscalerBean.getPlatform(),
                serviceInstance.getSpaceGuid(), serviceInstance.getOrganizationGuid());

        Binding binding = new Binding(bindingId, appGuid, "unknown", autoscalerBean.getScalerId(),
                serviceInstance.getId(), System.currentTimeMillis(), context);

        HttpEntity<Binding> request = new HttpEntity<>(binding, headers);

        String url = this.endpoint + BINDING_ENDPOINT;

        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(url, request, String.class);
        } catch (HttpClientErrorException ex) {
            log.error("Request to the autoscaler core raised an " + ex.getRawStatusCode() + " error.");
            response = new ResponseEntity<>(ex.getResponseBodyAsString(), HttpStatus.valueOf(ex.getRawStatusCode()));
        }
        return response;
    }

    private ResponseEntity<String> delete(String bindingId, String instanceId) {
        HttpEntity request = new HttpEntity(headers);

        String url = this.endpoint + BINDING_ENDPOINT + "/" + bindingId;

        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.DELETE, request, String.class);
        } catch (HttpClientErrorException ex) {
            log.error("Delete Binding Request to the autoscaler core raised an " + ex.getRawStatusCode() + " error.");
            response = new ResponseEntity<>(ex.getResponseBodyAsString(), HttpStatus.valueOf(ex.getRawStatusCode()));
        }
        return response;
    }
}
