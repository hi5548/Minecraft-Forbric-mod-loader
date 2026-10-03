package net.forbric.kernel.mixin;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import net.forbric.kernel.TestFixtures;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
/** Reads unmodified upstream modules; no test-written stand-in for their injection contracts. */
final class StagedFabricMixinFixture {
 static ClassNode mixin(String module,String name)throws Exception{
  Path api=TestFixtures.fabricApi();assumeTrue(Files.isRegularFile(api),"actual Fabric API fixture required");
  try(ZipFile z=new ZipFile(api.toFile())){
   ZipEntry e=z.stream().filter(x->x.getName().startsWith("META-INF/jars/"+module+"-")).findFirst().orElseThrow();
   try(ZipInputStream inner=new ZipInputStream(z.getInputStream(e))){for(ZipEntry entry;(entry=inner.getNextEntry())!=null;)if(entry.getName().equals(name+".class"))return MixinFit.parse(inner.readAllBytes());}
  }
  throw new AssertionError("actual mixin not found: "+name);
 }
 static ClassNode living(boolean vanilla)throws Exception{
  return game("net/minecraft/world/entity/LivingEntity",vanilla);
 }
 /**
  * A staged module's own mixin config, verbatim, or empty when the fixture is not staged. The ASSUMPTION lives at
  * the call site: JUnit's abort cannot unwind through a stream lambda, and a helper that throws it from one turns
  * "fixture absent" into a failure.
  */
 static java.util.Optional<String> mixinConfigText(String module,String config)throws Exception{
  Path api=TestFixtures.fabricApi();
  if(!Files.isRegularFile(api))return java.util.Optional.empty();
  try(ZipFile z=new ZipFile(api.toFile())){
   ZipEntry e=z.stream().filter(x->x.getName().startsWith("META-INF/jars/"+module+"-")).findFirst().orElseThrow();
   try(ZipInputStream inner=new ZipInputStream(z.getInputStream(e))){
    for(ZipEntry entry;(entry=inner.getNextEntry())!=null;)
     if(entry.getName().equals(config))return java.util.Optional.of(new String(inner.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
   }
  }
  throw new AssertionError("mixin config not found: "+module+"/"+config);
 }
 /** The merged base under the root this build compiled against, whatever version is staged there. */
 static ClassNode merged(String name)throws Exception{
  Path p=TestFixtures.mergedBase();
  TestFixtures.requireFiles("staged merged base",p);
  try(ZipFile z=new ZipFile(p.toFile())){return MixinFit.parse(z.getInputStream(z.getEntry(name+".class")).readAllBytes());}
 }
 static ClassNode game(String name,boolean vanilla)throws Exception{
  Path p=vanilla?TestFixtures.vanillaJar():
    Path.of(System.getenv().getOrDefault("FORBRIC_OLD","../forbric-loader"),"run/merged-base/patched-mc-merged-26.2.jar");
  assumeTrue(Files.isRegularFile(p),"actual game required");
  try(ZipFile z=new ZipFile(p.toFile())){return MixinFit.parse(z.getInputStream(z.getEntry(name+".class")).readAllBytes());}
 }
 static MethodNode method(ClassNode c,String name){return c.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
 static AnnotationNode at(ClassNode c,String name){return MixinFit.atNodes(MixinFit.injectorOf(method(c,name))).getFirst();}
 static byte[] bytes(ClassNode c){ClassWriter w=new ClassWriter(0);c.accept(w);return w.toByteArray();}
}
