package ir.electricconnect.nativefree;

interface DiscoveryProvider {
    String name();
    void discover(DiscoveryCoordinator.Run run) throws Exception;
}
