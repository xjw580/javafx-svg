package rc.consumer;
import club.xiaojiawei.svg.SvgLoader;
public final class ModuleApiConsumer {
    public static void main(String[] args) {
        if (!"club.xiaojiawei.svg".equals(SvgLoader.class.getModule().getName())) throw new AssertionError("unstable module name");
        if (!SvgLoader.parse("<svg/>").getRoot().getName().equals("svg")) throw new AssertionError();
        System.out.println("MODULE_CONSUMER_OK");
    }
}
