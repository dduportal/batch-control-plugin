package io.jenkins.plugins.batchcontrol;

import hudson.model.AdministrativeMonitor;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.ops.ConfigureWithoutGrantMonitor;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave D, group D4, issue #7, SPEC item 8 (D-86; matrix rows T-08-280..285, note 363): a holder of
 * Overall/Administer sees an "Administrator checks" section on the Batch Control overview
 * ({@code batch-control/}) that lists the findings of the {@code batch-control-strategy} and
 * configure-without-grant administrative monitors, each with its remedy, also while a monitor is
 * disabled or dismissed. Its buttons post to the existing monitor endpoints (migrate, revert,
 * markReviewed), which keep their Administer check. The monitors stay and link to the section.
 * Nobody else sees it (BatchControl/Manage only, Request only, approvers). Item and group names are
 * escaped. The missing-strategy warning comes from {@code batch-control-strategy} alone:
 * {@link ConfigureWithoutGrantMonitor#isActivated()} is false for a missing strategy while that
 * monitor is enabled (core-dev).
 *
 * <p>"The section" is the content under the heading ({@code h2} or {@code h3}) reading
 * "Administrator checks": its closest {@code section} ancestor in the main panel, else the
 * heading's following siblings up to the next heading of the same or a higher level. Texts are
 * read with {@code getTextContent()} (hidden parts included), so a collapsed section still counts
 * as shown and a hidden one as present.
 *
 * <p>Fixture: change control on; the Batch Control matrix strategy with StrategyFixtures' users
 * (admin Administer; c1 Item/Configure; m1 BatchControl/Manage; a1 Approve; bob RequestGrant),
 * r1 holding BatchControl/Request only and the group {@code <i>grp</i>} holding Item/Configure; no
 * build authenticator, so both monitors are active.
 *
 * <p>Written from SPEC 8 (D-86), the Wave D contract (D4, frozen 2026-10-10), issue #7 and pages
 * rendered on origin/main (d80e5fe); no src/main knowledge.
 */
@WithJenkins
public class AdminChecksTest {

    static final String HEADING = "Administrator checks";
    private static final String STRATEGY = "batch-control-strategy";
    private static final String CWG = ConfigureWithoutGrantMonitor.class.getName();
    private static final String GROUP = "<i>grp</i>";
    /** Allowed by core's name check, so it reaches text and attributes. */
    private static final String ODD_NAME = "q\"x'y";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        StrategyFixtures.changeControlOn();
        j.jenkins.setAuthorizationStrategy(withExtras(new BatchControlMatrixAuthorizationStrategy()));
    }

    /**
     * T-08-280 (D-86): with both monitors active, the administrator's overview has the section; it
     * names the Configure holders c1 and {@code <i>grp</i>} (escaped: the literal text, no {@code i}
     * element) with the remedy link to the security configuration, and the build-authorization
     * finding of {@code batch-control-strategy}. Guard: both monitors are activated.
     */
    @Test
    public void t_08_280_administratorSeesBothMonitorsFindingsWithRemedies() throws Exception {
        assertTrue(monitor(STRATEGY).isActivated(), "guard: batch-control-strategy is active (no build authenticator)");
        assertTrue(monitor(CWG).isActivated(), "guard: configure-without-grant is active (c1, the group)");

        HtmlPage overview = page("admin", "batch-control/");
        List<DomElement> section = section(overview);
        String text = text(section);
        assertTrue(text.contains("c1"), "the section must name the Configure holder c1: " + text);
        assertTrue(text.contains(GROUP), "the section must name the group literally (escaped): " + text);
        for (DomElement root : section) {
            for (DomNode i : root.querySelectorAll("i")) {
                assertFalse(i.getTextContent().contains("grp"), "the group name must not become markup: " + root.asXml());
            }
        }
        assertTrue(text.toLowerCase(Locale.ROOT).contains("system"), "the section must list the build-authorization finding"
                + " of batch-control-strategy (builds run as SYSTEM): " + text);
        assertTrue(links(section).contains(path("manage/configureSecurity")), "the findings' remedy (the security"
                + " configuration) must be linked from the section: " + links(section));
    }

    /**
     * T-08-281 (D-86, UX-2): with a plain project matrix installed (the strategy is missing) and a
     * build authenticator configured, the warning comes from batch-control-strategy alone:
     * configure-without-grant is not activated and not on Manage Jenkins. The section lists the
     * missing strategy with a control posting to the monitor's migrate endpoint; a crumbed POST to
     * that target installs the Batch Control matrix strategy. Guard: m1 is refused migrate (403).
     */
    @Test
    public void t_08_281_missingStrategyComesFromTheStrategyMonitorAndMigratesFromTheSection() throws Exception {
        StrategyFixtures.configureBuildAuthenticator();
        j.jenkins.setAuthorizationStrategy(withExtras(new ProjectMatrixAuthorizationStrategy()));
        assertTrue(monitor(STRATEGY).isEnabled() && monitor(STRATEGY).isActivated(),
                "guard: batch-control-strategy warns about the missing strategy");
        assertEquals(403, post("m1", "manage/administrativeMonitor/" + STRATEGY + "/migrate"),
                "guard: migrate keeps its Overall/Administer check");

        assertFalse(monitor(CWG).isActivated(), "configure-without-grant must not warn about a missing strategy while"
                + " batch-control-strategy is enabled (D-86)");
        assertTrue(page("admin", "manage/").querySelector("div[data-monitor-id='" + CWG + "']") == null,
                "Manage Jenkins must show the missing-strategy warning once, from batch-control-strategy");

        HtmlPage overview = page("admin", "batch-control/");
        List<DomElement> section = section(overview);
        String migrate = target(section, "administrativeMonitor/" + STRATEGY + "/migrate");
        assertNotNull(migrate, "the section must offer the missing strategy's remedy (migrate): " + text(section));

        assertTrue(postTo("admin", migrate) < 400, "the section's migrate control must post to the working endpoint");
        assertSame(BatchControlMatrixAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "migrate installs the Batch Control matrix strategy");
    }

    /**
     * T-08-282 (D-86, #7 "quote item names"): bob saves job {@code q"x'y} under a CONFIGURE window
     * (a build authenticator configured), so batch-control-strategy lists the changed item. The
     * section names it exactly and offers a control posting to markReviewed for it; using it clears
     * the item from the section.
     */
    @Test
    public void t_08_282_changedItemIsListedEscapedAndReviewedFromTheSection() throws Exception {
        StrategyFixtures.configureBuildAuthenticator();
        FreeStyleProject job = j.createFreeStyleProject(ODD_NAME);
        String window = ApproverFormFixtures.submitGrantOk(j, "bob", ODD_NAME, List.of("CONFIGURE"), 30, "edit", null, "a1");
        ApproverFormFixtures.assertSuccess(ApproverFormFixtures.decideGrant(j, "a1", window, "approve", "ok"), "fixture: approval");
        String xml = job.getConfigFile().asString();
        JenkinsRule.WebClient bob = ApproverFormFixtures.client(j, "bob");
        WebRequest save = new WebRequest(bob.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        save.setAdditionalHeader("Content-Type", "application/xml");
        save.setRequestBody(GuardReviewGapTest.withDescription(xml, "edited under a window"));
        assertTrue(bob.getPage(save).getWebResponse().getStatusCode() < 400, "fixture: bob saves the job under the window");
        assertTrue(monitor(STRATEGY).isActivated(), "guard: batch-control-strategy lists the changed item");

        HtmlPage overview = page("admin", "batch-control/");
        List<DomElement> section = section(overview);
        assertTrue(text(section).contains(ODD_NAME), "the section must name the changed item exactly: " + text(section));
        HtmlElement review = control(section, "administrativeMonitor/" + STRATEGY + "/markReviewed");
        assertNotNull(review, "the section must offer Mark as reviewed for the changed item: " + text(section));

        // what the control submits: its form's fields to the form action, or its data-url
        String submitted;
        HtmlForm form = review.getEnclosingForm();
        if (form != null && review.getAttribute("data-url").isEmpty()) {
            StringBuilder url = new StringBuilder(overview.getFullyQualifiedUrl(
                    review.getAttribute("formaction").isEmpty() ? form.getActionAttribute() : review.getAttribute("formaction"))
                    .toString());
            char sep = url.indexOf("?") < 0 ? '?' : '&';
            for (DomNode n : form.querySelectorAll("input[name]")) {
                DomElement input = (DomElement) n;
                if (!input.getAttribute("name").contains("rumb") && !"submit".equals(input.getAttribute("type"))) {
                    url.append(sep).append(java.net.URLEncoder.encode(input.getAttribute("name"), "UTF-8")).append('=')
                            .append(java.net.URLEncoder.encode(input.getAttribute("value"), "UTF-8"));
                    sep = '&';
                }
            }
            submitted = url.toString();
        } else {
            submitted = overview.getFullyQualifiedUrl(review.getAttribute("data-url")).toString();
        }
        assertTrue(postTo("admin", submitted) < 400, "Mark as reviewed from the section must work: " + submitted);
        assertFalse(text(section(page("admin", "batch-control/"))).contains(ODD_NAME),
                "after the review the item is no longer listed");
    }

    /**
     * T-08-283 (D-86): after the administrator dismisses both monitors (core's POST
     * {@code administrativeMonitor/<id>/disable}), the section still lists both monitors' findings.
     */
    @Test
    public void t_08_283_findingsStayListedAfterTheMonitorsAreDismissed() throws Exception {
        for (String id : List.of(STRATEGY, CWG)) {
            assertTrue(post("admin", "administrativeMonitor/" + id + "/disable") < 400, "fixture: dismiss " + id);
            assertFalse(monitor(id).isEnabled(), "fixture: " + id + " is disabled");
        }
        String text = text(section(page("admin", "batch-control/")));
        assertTrue(text.contains("c1") && text.contains(GROUP), "the configure-without-grant findings stay listed: " + text);
        assertTrue(text.toLowerCase(Locale.ROOT).contains("system"), "the batch-control-strategy finding stays listed: " + text);
    }

    /**
     * T-08-284 (D-86): on Manage Jenkins each of the two monitors links to the Batch Control
     * overview (the section), with or without a fragment.
     */
    @Test
    public void t_08_284_monitorsLinkToTheSection() throws Exception {
        HtmlPage manage = page("admin", "manage/");
        for (String id : List.of(STRATEGY, CWG)) {
            DomNode monitor = manage.querySelector("div[data-monitor-id='" + id + "']");
            assertNotNull(monitor, "guard: the " + id + " monitor is shown");
            List<String> hrefs = new ArrayList<>();
            boolean linked = false;
            for (DomNode a : monitor.querySelectorAll("a[href]")) {
                URL url = manage.getFullyQualifiedUrl(((DomElement) a).getAttribute("href"));
                hrefs.add(url.toString());
                linked |= url.getPath().equals(path("batch-control/"));
            }
            assertTrue(linked, "the " + id + " monitor must link to the Batch Control overview's section: " + hrefs);
        }
    }

    /**
     * T-08-285 (D-86, permission check): m1 (BatchControl/Manage), r1 (BatchControl/Request) and a1
     * (Approve) get no section: no heading text, no finding, no control posting to a monitor
     * endpoint, hidden parts included. Guard: the administrator gets it on the same instance.
     */
    @Test
    public void t_08_285_nobodyButAdministratorsSeesTheSection() throws Exception {
        for (String user : List.of("m1", "r1", "a1")) {
            Page answer = get(user, "batch-control/");
            int code = answer.getWebResponse().getStatusCode();
            assertTrue(code < 500, user + ": the overview must not fail, got " + code);
            String html = answer.getWebResponse().getContentAsString();
            assertFalse(html.contains(HEADING), user + " must not get the " + HEADING + " section");
            assertFalse(html.contains("administrativeMonitor/"), user + " must not get a control posting to a monitor endpoint");
            assertFalse(html.contains("&lt;i&gt;grp") || html.contains(GROUP), user + " must not see the findings");
        }
        assertFalse(section(page("admin", "batch-control/")).isEmpty(), "the administrator gets the " + HEADING + " section");
    }

    // ------------------------------------------------------------------ helpers

    private static <T extends hudson.security.GlobalMatrixAuthorizationStrategy> T withExtras(T strategy) {
        StrategyFixtures.matrix(strategy);
        strategy.add(Jenkins.READ, PermissionEntry.user("r1"));
        strategy.add(Item.READ, PermissionEntry.user("r1"));
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("r1"));
        strategy.add(Jenkins.READ, PermissionEntry.group(GROUP));
        strategy.add(Item.CONFIGURE, PermissionEntry.group(GROUP));
        return strategy;
    }

    private static AdministrativeMonitor monitor(String id) {
        AdministrativeMonitor monitor = Jenkins.get().getAdministrativeMonitor(id);
        assertNotNull(monitor, "the monitor " + id + " must be registered");
        return monitor;
    }

    /** The section under the "Administrator checks" heading (empty if there is none). */
    static List<DomElement> section(HtmlPage page) {
        for (DomNode n : page.querySelectorAll("#main-panel h2, #main-panel h3")) {
            if (!HEADING.equals(n.getTextContent().trim())) {
                continue;
            }
            DomElement heading = (DomElement) n;
            for (DomNode p = heading.getParentNode(); p instanceof DomElement e && !"main-panel".equals(e.getId());
                    p = p.getParentNode()) {
                if ("section".equals(e.getTagName())) {
                    return List.of(e);
                }
            }
            List<DomElement> out = new ArrayList<>();
            out.add(heading);
            int level = heading.getTagName().charAt(1) - '0';
            for (DomElement s = heading.getNextElementSibling(); s != null; s = s.getNextElementSibling()) {
                if (s.getTagName().matches("h[1-6]") && s.getTagName().charAt(1) - '0' <= level) {
                    break;
                }
                out.add(s);
            }
            return out;
        }
        return List.of();
    }

    private static String text(List<DomElement> section) {
        assertFalse(section.isEmpty(), "the overview must have the '" + HEADING + "' section");
        StringBuilder out = new StringBuilder();
        for (DomElement e : section) {
            out.append(e.getTextContent()).append('\n');
        }
        return out.toString();
    }

    private static List<String> links(List<DomElement> section) {
        List<String> out = new ArrayList<>();
        for (DomElement root : section) {
            for (DomNode a : root.querySelectorAll("a[href]")) {
                String href = ((DomElement) a).getAttribute("href");
                try {
                    out.add(((HtmlPage) root.getPage()).getFullyQualifiedUrl(href).getPath());
                } catch (java.net.MalformedURLException e) {
                    out.add(href);
                }
            }
        }
        return out;
    }

    /** The control in the section whose target (form action, formaction, data-url or href) contains {@code endpoint}. */
    private static HtmlElement control(List<DomElement> section, String endpoint) {
        for (DomElement root : section) {
            for (DomNode n : root.querySelectorAll("*")) {
                if (!(n instanceof HtmlElement e)) {
                    continue;
                }
                if (e instanceof HtmlForm f) {
                    if (f.getActionAttribute().contains(endpoint)) {
                        for (DomNode b : f.querySelectorAll("button, input[type=submit]")) {
                            return (HtmlElement) b;
                        }
                    }
                    continue;
                }
                for (String attribute : new String[] {"formaction", "data-url", "href"}) {
                    if (e.getAttribute(attribute).contains(endpoint)) {
                        return e;
                    }
                }
            }
        }
        return null;
    }

    /** The absolute URL the section's control for {@code endpoint} submits to, or null. */
    private static String target(List<DomElement> section, String endpoint) throws Exception {
        HtmlElement control = control(section, endpoint);
        if (control == null) {
            return null;
        }
        HtmlPage page = (HtmlPage) control.getPage();
        for (String attribute : new String[] {"formaction", "data-url", "href"}) {
            if (control.getAttribute(attribute).contains(endpoint)) {
                return page.getFullyQualifiedUrl(control.getAttribute(attribute)).toString();
            }
        }
        return page.getFullyQualifiedUrl(control.getEnclosingForm().getActionAttribute()).toString();
    }

    private String path(String relative) throws Exception {
        return new URL(j.getURL(), relative).getPath();
    }

    private int post(String user, String relative) throws Exception {
        JenkinsRule.WebClient wc = client(user);
        return wc.getPage(new WebRequest(wc.createCrumbedUrl(relative), HttpMethod.POST)).getWebResponse().getStatusCode();
    }

    /** A crumbed POST to an absolute target URL (keeps its query, for example markReviewed's item). */
    private int postTo(String user, String absolute) throws Exception {
        JenkinsRule.WebClient wc = client(user);
        URL target = new URL(absolute);
        String crumbed = wc.createCrumbedUrl(target.getPath().substring(j.contextPath.length() + 1)).toString();
        if (target.getQuery() != null) {
            crumbed += "&" + target.getQuery();
        }
        return wc.getPage(new WebRequest(new URL(crumbed), HttpMethod.POST)).getWebResponse().getStatusCode();
    }

    private HtmlPage page(String user, String relative) throws Exception {
        Page answer = get(user, relative);
        assertEquals(200, answer.getWebResponse().getStatusCode(), user + " GET " + relative);
        return (HtmlPage) answer;
    }

    private Page get(String user, String relative) throws Exception {
        return client(user).getPage(new WebRequest(new URL(j.getURL(), relative), HttpMethod.GET));
    }

    private JenkinsRule.WebClient client(String user) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        wc.getOptions().setJavaScriptEnabled(false);
        return wc;
    }
}
