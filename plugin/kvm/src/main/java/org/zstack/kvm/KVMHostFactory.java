package org.zstack.kvm;

import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.util.UriComponentsBuilder;
import org.zstack.compute.host.HostGlobalConfig;
import org.zstack.core.CoreGlobalProperty;
import org.zstack.core.ansible.AnsibleFacade;
import org.zstack.core.cloudbus.*;
import org.zstack.core.componentloader.PluginRegistry;
import org.zstack.core.config.GlobalConfig;
import org.zstack.core.config.GlobalConfigException;
import org.zstack.core.config.GlobalConfigUpdateExtensionPoint;
import org.zstack.core.config.GlobalConfigValidatorExtensionPoint;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.Q;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.notification.N;
import org.zstack.core.thread.AsyncThread;
import org.zstack.header.AbstractService;
import org.zstack.header.Component;
import org.zstack.header.errorcode.OperationFailureException;
import org.zstack.header.exception.CloudRuntimeException;
import org.zstack.header.host.*;
import org.zstack.header.managementnode.ManagementNodeReadyExtensionPoint;
import org.zstack.header.message.Message;
import org.zstack.header.message.MessageReply;
import org.zstack.header.message.NeedReplyMessage;
import org.zstack.header.network.l2.L2NetworkType;
import org.zstack.header.rest.RESTFacade;
import org.zstack.header.rest.SyncHttpCallHandler;
import org.zstack.header.tag.FormTagExtensionPoint;
import org.zstack.header.tag.SystemTagValidator;
import org.zstack.header.volume.MaxDataVolumeNumberExtensionPoint;
import org.zstack.header.volume.VolumeConstant;
import org.zstack.header.volume.VolumeFormat;
import org.zstack.kvm.KVMAgentCommands.ReconnectMeCmd;
import org.zstack.utils.CollectionUtils;
import org.zstack.utils.IpRangeSet;
import org.zstack.utils.SizeUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.form.Form;
import org.zstack.utils.function.Function;
import org.zstack.utils.logging.CLogger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.zstack.core.Platform.operr;

public class KVMHostFactory extends AbstractService implements HypervisorFactory, Component,
        ManagementNodeReadyExtensionPoint, MaxDataVolumeNumberExtensionPoint, HypervisorMessageFactory {
    private static final CLogger logger = Utils.getLogger(KVMHostFactory.class);

    public static final HypervisorType hypervisorType = new HypervisorType(KVMConstant.KVM_HYPERVISOR_TYPE);
    public static final VolumeFormat QCOW2_FORMAT = new VolumeFormat(VolumeConstant.VOLUME_FORMAT_QCOW2, hypervisorType);
    public static final VolumeFormat RAW_FORMAT = new VolumeFormat(VolumeConstant.VOLUME_FORMAT_RAW, hypervisorType);
    private List<KVMHostConnectExtensionPoint> connectExtensions = new ArrayList<>();
    private Map<L2NetworkType, KVMCompleteNicInformationExtensionPoint> completeNicInfoExtensions = new HashMap<>();
    private int maxDataVolumeNum;

    static {
        RAW_FORMAT.newFormatInputOutputMapping(hypervisorType, QCOW2_FORMAT.toString());
        QCOW2_FORMAT.setFirstChoice(hypervisorType);
    }

    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private PluginRegistry pluginRgty;
    @Autowired
    private AnsibleFacade asf;
    @Autowired
    private ResourceDestinationMaker destMaker;
    @Autowired
    private CloudBus bus;
    @Autowired
    private RESTFacade restf;

    @Override
    public HostVO createHost(HostVO vo, AddHostMessage msg) {
        AddKVMHostMessage amsg = (AddKVMHostMessage) msg;
        KVMHostVO kvo = new KVMHostVO(vo);
        kvo.setUsername(amsg.getUsername());
        kvo.setPassword(amsg.getPassword());
        kvo.setPort(amsg.getSshPort());
        kvo = dbf.persistAndRefresh(kvo);
        return kvo;
    }

    @Override
    public List<AddHostMsg> buildMessageFromFile(String content) {
        try {
            return loadMsgFromFile(content).stream()
                    .peek(it -> it.setName((StringUtils.isEmpty(it.getName()) ? "HOST" : it.getName()) + "-" + it.getManagementIp()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new OperationFailureException(operr("fail to load host info from file. because\n%s", e.getMessage()));
        }
    }

    private List<AddKVMHostMsg> loadMsgFromFile(String content) throws IOException {
        Map<String, Function<String, String>> extensionTagMappers = new HashMap<>();
        pluginRgty.getExtensionList(FormTagExtensionPoint.class).forEach(it -> extensionTagMappers.putAll(it.getTagMappers(AddKVMHostMsg.class)));

        Form<AddKVMHostMsg> form = Form.New(AddKVMHostMsg.class, content)
                .addHeaderConverter(head -> (head.matches(".*\\(.*\\).*") ? head.split("[()]")[1] : head)
                        .replaceAll("\\*", ""))
                .addColumnConverter("managementIps", IpRangeSet::listAllIps, AddHostMsg::setManagementIp);

        extensionTagMappers.forEach((columnName, builder) ->
                form.addColumnConverter(columnName, (it, value) -> it.addSystemTag(builder.call(value))));

        return form.load();
    }

    @Override
    public Host getHost(HostVO vo) {
        KVMHostVO kvo = dbf.findByUuid(vo.getUuid(), KVMHostVO.class);
        KVMHostContext context = getHostContext(vo.getUuid());
        if (context == null) {
            context = createHostContext(kvo);
        }
        return new KVMHost(kvo, context);
    }

    private List<String> getHostManagedByUs() {
        int qun = 10000;
        long amount = dbf.count(HostVO.class);
        int times = (int) (amount / qun) + (amount % qun != 0 ? 1 : 0);
        List<String> hostUuids = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < times; i++) {
            SimpleQuery<KVMHostVO> q = dbf.createQuery(KVMHostVO.class);
            q.select(HostVO_.uuid);
            // disconnected host will be handled by HostManager
            q.add(HostVO_.status, SimpleQuery.Op.EQ, HostStatus.Connected);
            q.setLimit(qun);
            q.setStart(start);
            List<String> lst = q.listValue();
            start += qun;
            for (String huuid : lst) {
                if (!destMaker.isManagedByUs(huuid)) {
                    continue;
                }
                hostUuids.add(huuid);
            }
        }

        return hostUuids;
    }

    @Override
    public HypervisorType getHypervisorType() {
        return hypervisorType;
    }

    @Override
    public HostInventory getHostInventory(HostVO vo) {
        KVMHostVO kvo = vo instanceof KVMHostVO ? (KVMHostVO) vo : dbf.findByUuid(vo.getUuid(), KVMHostVO.class);
        return KVMHostInventory.valueOf(kvo);
    }

    @Override
    public HostInventory getHostInventory(String uuid) {
        KVMHostVO vo = dbf.findByUuid(uuid, KVMHostVO.class);
        return vo == null ? null : KVMHostInventory.valueOf(vo);
    }

    private void populateExtensions() {
        connectExtensions = pluginRgty.getExtensionList(KVMHostConnectExtensionPoint.class);
        for (KVMCompleteNicInformationExtensionPoint ext : pluginRgty.getExtensionList(KVMCompleteNicInformationExtensionPoint.class)) {
            KVMCompleteNicInformationExtensionPoint old = completeNicInfoExtensions.get(ext.getL2NetworkTypeVmNicOn());
            if (old != null) {
                throw new CloudRuntimeException(String.format("duplicate KVMCompleteNicInformationExtensionPoint[%s, %s] for type[%s]",
                        old.getClass().getName(), ext.getClass().getName(), ext.getL2NetworkTypeVmNicOn()));
            }
            completeNicInfoExtensions.put(ext.getL2NetworkTypeVmNicOn(), ext);
        }
    }

    public KVMCompleteNicInformationExtensionPoint getCompleteNicInfoExtension(L2NetworkType type) {
        KVMCompleteNicInformationExtensionPoint extp = completeNicInfoExtensions.get(type);
        if (extp == null) {
            throw new IllegalArgumentException(String.format("unble to fine KVMCompleteNicInformationExtensionPoint supporting L2NetworkType[%s]", type));
        }
        return extp;
    }

    private void deployAnsibleModule() {
        if (CoreGlobalProperty.UNIT_TEST_ON) {
            return;
        }

        asf.deployModule(KVMConstant.ANSIBLE_MODULE_PATH, KVMConstant.ANSIBLE_PLAYBOOK_NAME);
    }

    @Override
    public boolean start() {
        deployAnsibleModule();
        populateExtensions();

        maxDataVolumeNum = KVMGlobalConfig.MAX_DATA_VOLUME_NUM.value(int.class);
        KVMGlobalConfig.MAX_DATA_VOLUME_NUM.installUpdateExtension(new GlobalConfigUpdateExtensionPoint() {
            @Override
            public void updateGlobalConfig(GlobalConfig oldConfig, GlobalConfig newConfig) {
                maxDataVolumeNum = newConfig.value(int.class);
            }
        });
        KVMGlobalConfig.RESERVED_MEMORY_CAPACITY.installValidateExtension(new GlobalConfigValidatorExtensionPoint() {
            @Override
            public void validateGlobalConfig(String category, String name, String oldValue, String value) throws GlobalConfigException {
                if (!SizeUtils.isSizeString(value)) {
                    throw new GlobalConfigException(String.format("%s only allows a size string." +
                                    " A size string is a number with suffix 'T/t/G/g/M/m/K/k/B/b' or without suffix, but got %s",
                            KVMGlobalConfig.RESERVED_MEMORY_CAPACITY.getCanonicalName(), value));
                }
            }
        });
        KVMGlobalConfig.RESERVED_MEMORY_CAPACITY.installValidateExtension(new GlobalConfigValidatorExtensionPoint() {
            @Override
            public void validateGlobalConfig(String category, String name, String oldValue, String value) throws GlobalConfigException {
                Long valueLong = SizeUtils.sizeStringToBytes(value);
                Long _1t = SizeUtils.sizeStringToBytes("1T");
                if (valueLong > _1t || valueLong < 0) {
                    throw new GlobalConfigException(String.format("Value %s  cannot be greater than the 1TB" + " but got %s",
                            KVMGlobalConfig.RESERVED_MEMORY_CAPACITY.getCanonicalName(), value));
                }
            }
        });
        restf.registerSyncHttpCallHandler(KVMConstant.KVM_RECONNECT_ME, ReconnectMeCmd.class, new SyncHttpCallHandler<ReconnectMeCmd>() {
            @Override
            public String handleSyncHttpCall(ReconnectMeCmd cmd) {
                N.New(HostVO.class, cmd.hostUuid).info_("the kvm host[uuid:%s] asks the management server to reconnect it for %s", cmd.hostUuid, cmd.reason);
                ReconnectHostMsg msg = new ReconnectHostMsg();
                msg.setHostUuid(cmd.hostUuid);
                bus.makeTargetServiceIdByResourceUuid(msg, HostConstant.SERVICE_ID, cmd.hostUuid);
                bus.send(msg);
                return null;
            }
        });

        KVMSystemTags.CHECK_CLUSTER_CPU_MODEL.installValidator(((resourceUuid, resourceType, systemTag) -> {
            Map<String, String> hostModelMap = getHostsWithDiffModel(resourceUuid);

            if (hostModelMap.values().stream().distinct().collect(Collectors.toList()).size() != 1) {
                String str = "";
                for (Map.Entry entry : hostModelMap.entrySet()) {
                    str += String.format("host[uuid:%s]'s cpu model is %s ;\n", entry.getKey(), entry.getValue());
                }

                throw new OperationFailureException(operr("there are still hosts not have the same cpu model, details: %s", str));
            }
        }));

        return true;
    }

    private Map<String, String> getHostsWithDiffModel(String clusterUuid) {
        List<String> hostUuidsInCluster = Q.New(HostVO.class)
                .select(HostVO_.uuid)
                .eq(HostVO_.clusterUuid, clusterUuid)
                .listValues();
        if (hostUuidsInCluster.isEmpty()) {
            return new HashMap<>();
        }

        Map<String, String> diffMap = new HashMap<>();
        for (String hostUuid : hostUuidsInCluster) {
            String hostCpuModel = KVMSystemTags.CPU_MODEL_NAME.getTokenByResourceUuid(hostUuid, KVMSystemTags.CPU_MODEL_NAME_TOKEN);

            if (hostCpuModel == null) {
                throw new OperationFailureException(operr("host[uuid:%s] does not have cpu model information, you can reconnect the host to fix it", hostUuid));
            }

            if (diffMap.values().stream().distinct().noneMatch(model -> model.equals(hostCpuModel))) {
                diffMap.put(hostUuid, hostCpuModel);
            }
        }

        return diffMap;
    }

    @Override
    public boolean stop() {
        return true;
    }

    public List<KVMHostConnectExtensionPoint> getConnectExtensions() {
        return connectExtensions;
    }

    public KVMHostContext createHostContext(KVMHostVO vo) {
        UriComponentsBuilder ub = UriComponentsBuilder.newInstance();
        ub.scheme(KVMGlobalProperty.AGENT_URL_SCHEME);
        ub.host(vo.getManagementIp());
        ub.port(KVMGlobalProperty.AGENT_PORT);
        if (!"".equals(KVMGlobalProperty.AGENT_URL_ROOT_PATH)) {
            ub.path(KVMGlobalProperty.AGENT_URL_ROOT_PATH);
        }
        String baseUrl = ub.build().toUriString();

        KVMHostContext context = new KVMHostContext();
        context.setInventory(KVMHostInventory.valueOf(vo));
        context.setBaseUrl(baseUrl);
        return context;
    }

    public KVMHostContext getHostContext(String hostUuid) {
        KVMHostVO kvo = dbf.findByUuid(hostUuid, KVMHostVO.class);
        return createHostContext(kvo);
    }

    @Override
    public String getHypervisorTypeForMaxDataVolumeNumberExtension() {
        return KVMConstant.KVM_HYPERVISOR_TYPE;
    }

    @Override
    public int getMaxDataVolumeNumber() {
        return maxDataVolumeNum;
    }

    @Override
    @AsyncThread
    public void managementNodeReady() {
        if (CoreGlobalProperty.UNIT_TEST_ON) {
            return;
        }

        if (!asf.isModuleChanged(KVMConstant.ANSIBLE_PLAYBOOK_NAME)) {
            return;
        }

        // KVM hosts need to deploy new agent
        // connect hosts even if they are ConnectionState is Connected

        List<String> hostUuids = getHostManagedByUs();
        if (hostUuids.isEmpty()) {
            return;
        }

        logger.debug(String.format("need to connect kvm hosts because kvm agent changed, uuids:%s", hostUuids));

        List<ConnectHostMsg> msgs = new ArrayList<ConnectHostMsg>();
        for (String huuid : hostUuids) {
            ConnectHostMsg msg = new ConnectHostMsg();
            msg.setNewAdd(false);
            msg.setUuid(huuid);
            bus.makeTargetServiceIdByResourceUuid(msg, HostConstant.SERVICE_ID, huuid);
            msgs.add(msg);
        }

        bus.send(msgs, HostGlobalConfig.HOST_LOAD_PARALLELISM_DEGREE.value(Integer.class), new CloudBusSteppingCallback(null) {
            @Override
            public void run(NeedReplyMessage msg, MessageReply reply) {
                ConnectHostMsg cmsg = (ConnectHostMsg) msg;
                if (!reply.isSuccess()) {
                    logger.warn(String.format("failed to connect kvm host[uuid:%s], %s", cmsg.getHostUuid(), reply.getError()));
                } else {
                    logger.debug(String.format("successfully to connect kvm host[uuid:%s]", cmsg.getHostUuid()));
                }
            }
        });
    }

    @Override
    @MessageSafe
    public void handleMessage(Message msg) {
        if (msg instanceof APIKvmRunShellMsg) {
            handle((APIKvmRunShellMsg) msg);
        } else {
            bus.dealWithUnknownMessage(msg);
        }
    }

    private void handle(final APIKvmRunShellMsg msg) {
        final APIKvmRunShellEvent evt = new APIKvmRunShellEvent(msg.getId());

        final List<KvmRunShellMsg> kmsgs = CollectionUtils.transformToList(msg.getHostUuids(), new Function<KvmRunShellMsg, String>() {
            @Override
            public KvmRunShellMsg call(String arg) {
                KvmRunShellMsg kmsg = new KvmRunShellMsg();
                kmsg.setHostUuid(arg);
                kmsg.setScript(msg.getScript());
                bus.makeTargetServiceIdByResourceUuid(kmsg, HostConstant.SERVICE_ID, arg);
                return kmsg;
            }
        });

        bus.send(kmsgs, new CloudBusListCallBack(msg) {
            @Override
            public void run(List<MessageReply> replies) {
                for (MessageReply r : replies) {
                    String hostUuid = kmsgs.get(replies.indexOf(r)).getHostUuid();

                    APIKvmRunShellEvent.ShellResult result = new APIKvmRunShellEvent.ShellResult();
                    if (!r.isSuccess()) {
                        result.setErrorCode(r.getError());
                    } else {
                        KvmRunShellReply kr = r.castReply();
                        result.setReturnCode(kr.getReturnCode());
                        result.setStderr(kr.getStderr());
                        result.setStdout(kr.getStdout());
                    }

                    evt.getInventory().put(hostUuid, result);
                }

                bus.publish(evt);
            }
        });
    }

    @Override
    public String getId() {
        return bus.makeLocalServiceId(KVMConstant.SERVICE_ID);
    }
}
