package org.jenkinsci.extension_indexer;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.maven.artifact.resolver.AbstractArtifactResolutionException;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.jvnet.hudson.update_center.ConfluencePluginList;
import org.jvnet.hudson.update_center.HudsonWar;
import org.jvnet.hudson.update_center.MavenArtifact;
import org.jvnet.hudson.update_center.MavenRepositoryImpl;
import org.jvnet.hudson.update_center.Plugin;
import org.jvnet.hudson.update_center.PluginHistory;
import org.sonatype.nexus.index.context.UnsupportedExistingLuceneIndexException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Command-line tool to list up extension points and their implementations into a JSON file.
 *
 * @author Kohsuke Kawaguchi
 */
public class ExtensionPointListGenerator {
    private final Map<String,Family> families = new HashMap<String,Family>();
    private final Map<MavenArtifact,Module> modules = new HashMap<MavenArtifact,Module>();


    /**
     * Relationship between definition and implementations of the extension points.
     */
    public class Family {
        Extension definition;
        final List<Extension> implementations = new ArrayList<Extension>();

        public String getName() {
            return definition.extensionPoint.getQualifiedName().toString();
        }

        void formatAsConfluencePage(PrintWriter w) {
            w.println("h2." + definition.extensionPoint.getQualifiedName());
            w.println(getSynopsis(definition));
            w.println(definition.getConfluenceDoc());
            w.println();
            w.println("{expand:title=Implementations}");
            for (Extension e : implementations) {
                w.println("h3."+e.implementation.getQualifiedName());
                w.println(getSynopsis(e));
                w.println(e.getConfluenceDoc());
            }
            if (implementations.isEmpty())
                w.println("(No known implementation)");
            w.println("{expand}");
            w.println("");
        }

        private String getSynopsis(Extension e) {
            final Module m = modules.get(e.artifact);
            if (m==null)
                throw new IllegalStateException("Unable to find module for "+e.artifact);
            return MessageFormat.format("*Defined in*: {0}  ([javadoc|{1}@javadoc])\n",
                    m.getWikiLink(), e.extensionPoint.getQualifiedName());
        }
    }

    /**
     * Information about the module that we scanned extensions.
     */
    abstract class Module {
        final MavenArtifact artifact;
        final String url;
        final String displayName;

        protected Module(MavenArtifact artifact, String url, String displayName) {
            this.artifact = artifact;
            this.url = url;
            this.displayName = displayName;
        }

        /**
         * Returns a Confluence-format link to point to this module.
         */
        abstract String getWikiLink();

        JSONObject toJSON() {
            JSONObject o = new JSONObject();
            o.put("gav",artifact.getGavId());
            o.put("url",url);
            o.put("displayName",displayName);
            return o;
        }
    }

    public static void main(String[] args) throws Exception {
        new ExtensionPointListGenerator().run();
    }

    public void run() throws Exception {
        MavenRepositoryImpl r = new MavenRepositoryImpl();
        r.addRemoteRepository("java.net2",
                new File("updates.jenkins-ci.org"),
                new URL("http://maven.glassfish.org/content/groups/public/"));

        final ConfluencePluginList cpl = new ConfluencePluginList();

        HudsonWar war = r.getHudsonWar().firstEntry().getValue();
        final MavenArtifact core = war.getCoreArtifact();
        discover(core);
        modules.put(core, new Module(core,"http://github.com/jenkinsci/jenkins/","Jenkins Core") {
            @Override
            String getWikiLink() {
                return "[Jenkins Core|Building Jenkins]";
            }
        });

        processPlugins(r, cpl);

        JSONObject all = new JSONObject();
        for (Family f : families.values()) {
            if (f.definition==null)     continue;   // skip undefined extension points
            JSONObject o = f.definition.toJSON();

            JSONArray use = new JSONArray();
            for (Extension impl : f.implementations)
                use.add(impl.toJSON());
            o.put("implementations", use);

            all.put(f.getName(),o);
        }

        // this object captures information about modules where extensions are defined/found.
        final JSONObject artifacts = new JSONObject();
        for (Module m : modules.values()) {
            artifacts.put(m.artifact.getGavId(),m.toJSON());
        }

        JSONObject container = new JSONObject();
        container.put("extensionPoints",all);
        container.put("artifacts",artifacts);

        FileUtils.writeStringToFile(new File("extension-points.json"), container.toString(2));

        generateConfluencePage();
    }

    private void processPlugins(MavenRepositoryImpl r, final ConfluencePluginList cpl) throws Exception {
        ExecutorService svc = Executors.newFixedThreadPool(4);
        try {
            Set<Future> futures = new HashSet<Future>();
            for (final PluginHistory p : new ArrayList<PluginHistory>(r.listHudsonPlugins())/*.subList(0,200)*/) {
                futures.add(svc.submit(new Runnable() {
                    public void run() {
                        try {
                            System.out.println(p.artifactId);
                            discover(p.latest());
                            synchronized (modules) {
                                Plugin pi = new Plugin(p,cpl);
                                modules.put(p.latest(), new Module(p.latest(),pi.getWiki(),pi.getTitle()) {
                                    @Override
                                    String getWikiLink() {
                                        return '['+displayName+']';
                                    }
                                });
                            }
                        } catch (Exception e) {
                            System.err.println("Failed to process "+p.artifactId);
                            e.printStackTrace();
                        }
                    }
                }));
            }
            for (Future f : futures) {
                f.get();
            }
        } finally {
            svc.shutdown();
        }
    }

    private void generateConfluencePage() throws FileNotFoundException {
        Map<Module,List<Family>> byModule = new LinkedHashMap<Module,List<Family>>();
        for (Family f : families.values()) {
            if (f.definition==null)     continue;   // skip undefined extension points

            Module key = modules.get(f.definition.artifact);
            List<Family> value = byModule.get(key);
            if (value==null)    byModule.put(key,value=new ArrayList<Family>());
            value.add(f);
        }

        PrintWriter w = new PrintWriter(new File("extension-points.page"));
        w.println("{toc:maxLevel=2}\n");
        for (Entry<Module, List<Family>> e : byModule.entrySet()) {
            w.println("h1.Extension Points in "+e.getKey().getWikiLink());
            for (Family f : e.getValue())
                f.formatAsConfluencePage(w);
        }
        w.close();
    }

    private void discover(MavenArtifact a) throws IOException, InterruptedException {
        for (Extension e : new ExtensionPointsExtractor(a).extract()) {
            synchronized (families) {
                System.out.printf("Found %s as %s\n",
                        e.implementation.getQualifiedName(),
                        e.extensionPoint.getQualifiedName());

                String key = e.extensionPoint.getQualifiedName().toString();
                Family f = families.get(key);
                if (f==null)    families.put(key,f=new Family());

                if (e.isDefinition()) {
                    assert f.definition==null;
                    f.definition = e;
                } else {
                    f.implementations.add(e);
                }
            }
        }
    }
}
