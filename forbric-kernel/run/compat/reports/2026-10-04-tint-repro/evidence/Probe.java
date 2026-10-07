import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Map;

/** Outside-the-artifact probe: prints the render-type decision for a few blocks once the client is up. */
public class Probe {
    public static void main(String[] a) { }

    public static void premain(String args, Instrumentation inst) {
        Thread t = new Thread(Probe::run, "forbric-probe");
        t.setDaemon(true);
        t.start();
    }

    static ClassLoader findLoader() {
        for (int i = 0; i < 120; i++) {
            for (Thread th : Thread.getAllStackTraces().keySet()) {
                ClassLoader cl = th.getContextClassLoader();
                if (cl == null) continue;
                try { Class.forName("net.minecraft.client.renderer.ItemBlockRenderTypes", false, cl); return cl; }
                catch (Throwable ignored) { }
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return null; }
        }
        return null;
    }

    static void run() {
        ClassLoader cl = findLoader();
        if (cl == null) { System.out.println("[probe] no game classloader found"); return; }
        try {
            Thread.sleep(15000); // let the client finish init (render types are set during Minecraft.<init>)
        } catch (InterruptedException e) { return; }
        try {
            Class<?> ibrt = Class.forName("net.minecraft.client.renderer.ItemBlockRenderTypes", true, cl);
            Class<?> blocks = Class.forName("net.minecraft.world.level.block.Blocks", true, cl);
            Class<?> blockState = Class.forName("net.minecraft.world.level.block.state.BlockState", true, cl);
            Method defaultState = Class.forName("net.minecraft.world.level.block.Block", true, cl).getMethod("defaultBlockState");
            Method getChunkRenderType = ibrt.getMethod("getChunkRenderType", blockState);
            Method getRenderLayers = ibrt.getMethod("getRenderLayers", blockState);
            var f = ibrt.getDeclaredField("renderCutout"); f.setAccessible(true);
            System.out.println("[probe] renderCutout=" + f.get(null));
            for (String name : new String[]{"GRASS_BLOCK", "OAK_LEAVES", "DANDELION", "GLASS", "STONE", "IRON_BARS", "OAK_PLANKS", "SHORT_GRASS"}) {
                Object block = blocks.getField(name).get(null);
                Object state = defaultState.invoke(block);
                Object rt = getChunkRenderType.invoke(null, state);
                Object layers = getRenderLayers.invoke(null, state);
                String material = "?";
                try {
                    Class<?> dm = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials", true, cl);
                    Object m = dm.getMethod("forBlockState", blockState).invoke(null, state);
                    material = "pass=" + m.getClass().getField("pass").get(m)
                            + " cutoff=" + m.getClass().getField("alphaCutoff").get(m)
                            + " mipped=" + m.getClass().getField("mipped").get(m);
                } catch (Throwable e) { material = "sodium-n/a: " + e; }
                System.out.println("[probe] " + name + " getChunkRenderType=" + rt + " getRenderLayers=" + layers
                        + " identity(rt==cutoutMipped)=" + (rt == Class.forName("net.minecraft.client.renderer.RenderType", true, cl).getMethod("cutoutMipped").invoke(null))
                        + " | " + material);
            }
            // also print the raw map sizes
            for (String field : new String[]{"TYPE_BY_BLOCK", "BLOCK_RENDER_TYPES", "TYPE_BY_FLUID", "FLUID_RENDER_TYPES"}) {
                try {
                    var fl = ibrt.getDeclaredField(field); fl.setAccessible(true);
                    Object v = fl.get(null);
                    System.out.println("[probe] " + field + " = " + (v == null ? "null" : v.getClass().getName() + " size=" + ((Map<?, ?>) v).size()));
                } catch (Throwable e) { System.out.println("[probe] " + field + " -> " + e); }
            }
        } catch (Throwable e) {
            System.out.println("[probe] failed: " + e);
            e.printStackTrace(System.out);
        }
    }
}
