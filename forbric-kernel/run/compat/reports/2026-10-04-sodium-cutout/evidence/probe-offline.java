package net.forbric.kernel.mixin;
import java.util.*;
import java.util.function.Function;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;
/** Reads the real merged-base SpriteContents and the real Sodium guest mixins, and shows what MixinStubRebind does with the carrier-stubs row. */
public final class SoProbeFinal {
    static ClassNode read(byte[] b){ClassNode n=new ClassNode();new ClassReader(b).accept(n,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);return n;}
    static byte[] fromZip(String z,String e)throws Exception{try(ZipFile f=new ZipFile(z)){return f.getInputStream(f.getEntry(e)).readAllBytes();}}
    public static void main(String[] a)throws Exception{
        ClassNode target=read(fromZip(a[0],"net/minecraft/client/renderer/texture/SpriteContents.class"));
        MethodNode stub=null, body=null;
        for(MethodNode m:target.methods) if(m.name.equals("<init>")){ if(Type.getArgumentTypes(m.desc).length==4) stub=m; else if(Type.getArgumentTypes(m.desc).length==5) body=m; }
        MixinStubRebind.Delegation d=MixinStubRebind.delegation(target,stub);
        System.out.println("merged-base SpriteContents:");
        System.out.println("  4-arg <init> = stub? " + (d!=null) + (d!=null ? ("  -> delegates to 5-arg " + d.delegate().desc) : ""));
        String key=target.name+"#"+stub.name+stub.desc+" -> "+(d==null?"?":d.delegate().desc);
        MixinStubRebind.Row row=MixinStubRebind.carrierStubs().get(key);
        System.out.println("  carrier-stubs row: " + (row!=null ? ("present (forge="+row.forge()+", neo="+row.neo()+")") : "ABSENT"));
        if(row!=null) System.out.println("  NeoForge family moves the name-only selector? " + row.moves(Ecosystem.NEOFORGE,true));
        Function<String,ClassNode> lookup=n->n.equals(target.name)?target:null;
        for(String entry: a[1].split(",")){
            ClassNode mixin=read(fromZip(a[2],entry));
            MixinStubRebind.noteEcosystem(mixin.name,Ecosystem.NEOFORGE,"sodium-common.mixins.json");
            System.out.println();
            System.out.println("guest " + mixin.name + ":");
            for(MethodNode m:mixin.methods){
                AnnotationNode inj=MixinFit.injectorOf(m);
                if(inj==null) continue;
                System.out.println("  handler " + m.name + " selector=" + MixinFit.value(inj,"method"));
                Type[] params=Type.getArgumentTypes(m.desc);
                System.out.println("    intrinsicArity(own)=" + MixinStubRebind.intrinsicArity(inj,params,params.length,body)
                        + "  destination=" + (MixinStubRebind.destination(mixin,m,target)!=null));
            }
            int moved=MixinStubRebind.adapt(mixin,lookup);
            System.out.println("  adapt() moved " + moved + " injector(s)");
            for(MethodNode m:mixin.methods){ if(m.visibleAnnotations==null) continue;
                for(AnnotationNode an:m.visibleAnnotations) if(an.desc.endsWith("WrapOperation"))
                    System.out.println("  resulting selector: " + MixinFit.value(an,"method")); }
        }
    }
}
