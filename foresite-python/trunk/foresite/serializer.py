
import re
from ore import *
from utils import namespaces
from rdflib import URIRef, BNode, plugin, syntax
from lxml import etree
from lxml.etree import Element, SubElement

plugin.register('rdfa', syntax.serializers.Serializer, 'foresite.RDFaSerializer', 'RDFaSerializer')

class ORESerializer(object):
    # Take objects and produce data
    mimeType = ""
    format = ""
    public = 1

    def __init__(self, format, public=1):
        mimetypes = {'atom' : 'application/atom+xml', 
                     'rdfa' : 'application/xhtml+xml',
                     'xml' : 'application/rdf+xml',
                     'nt' : 'text/plain',
                     'n3' : 'text/rdf+n2',
                     'turtle' : 'application/turtle',
                     'pretty-xml' : 'application/rdf+xml'
                     }
        self.format = format
        self.public = public
        self.mimeType = mimetypes.get(format, '')

    def merge_graphs(self, rem):
        g = Graph()
        if not rem._graph_.objects((rem._uri_, namespaces['dcterms']['creator'])):
            rem.add_agent(libraryAgent, 'creator')

        g += rem._graph_
        for at in rem._triples_:
            g += at._graph_
        for c in rem._agents_:
            g += c._graph_
        if not rem.created:
            g.add((rem._uri_, namespaces['dcterms']['created'], Literal(now())))
        g.add((rem._uri_, namespaces['dcterms']['modified'], Literal(now())))

        aggr = rem._aggregation_
        g += aggr._graph_
        for at in aggr._triples_:
            g += at._graph_
        for c in aggr._agents_:
            g += c._graph_
        for (res, proxy) in aggr._resources_:
            g += res._graph_
            g += proxy._graph_
            for at in res._triples_:
                g += at._graph_
            for c in res._agents_:
                g += c._graph_
            if isinstance(res, Aggregation):
                # don't recurse, remove aggregates                
                for a in res._ore.aggregates:
                    g.remove((res._uri_, namespaces['ore']['aggregates'], a))

        if self.public:
            # Remove internal methods
            for p in internalPredicates:
                for (s,o) in g.subject_objects(p):
                    g.remove((s,p,o))
        if not g.objects((aggr._uri_, namespaces['ore']['aggregates'])):
            raise OreException("Aggregation must aggregate something")

        # DISCUSS: Ensure connectedness.
        # This means can construct unconnected stuff, just doesn't get
        # serialised.  Should alert this?
        g = self.connected_graph(g, aggr._uri_)

        return g

    def connected_graph(self, graph, uri):
        g = Graph()
        all_nodes = list(graph.all_nodes())
        discovered = {}
        visiting = [uri]
        while visiting:
            x = visiting.pop()
            if not discovered.has_key(x):
                discovered[x] = 1
            for (p, new_x) in graph.predicate_objects(subject=x):
                g.add((x,p,new_x))
                if isinstance(new_x, URIRef) and not discovered.has_key(new_x) and not new_x in visiting:
                    visiting.append(new_x)
            for (new_x, p) in graph.subject_predicates(object=x):
                g.add((new_x,p,x))
                if isinstance(new_x, URIRef) and not discovered.has_key(new_x) and not new_x in visiting:
                    visiting.append(new_x)
        if len(discovered) != len(all_nodes):
            # print 'Input graph to serialize is not connected'
            pass
        return g


class RdfLibSerializer(ORESerializer):

    def serialize(self, rem):
        g = self.merge_graphs(rem)
        data = g.serialize(format=self.format)
        uri = str(rem._uri_)
        return ReMDocument(uri, data)

class AtomSerializer(ORESerializer):

    def __init__(self, format="atom", public=1):
        ORESerializer.__init__(self, format)
        self.spacesub = re.compile('(?<=>)[ ]+(?=<)')

    def remove_link_attrs(self, sg, a):
        # only remove first from each list
        for ns in (namespaces['dc']['format'], namespaces['dc']['title'], namespaces['dc']['language'], namespaces['dc']['extent']):
            objs = list(sg.objects(a, ns))
            if objs:
                sg.remove((a, ns, objs[0]))
        
    def generate_rdf(self, parent, what, graph):
        # extract not processed parts of graph
        # serialise with rdflib
        # parse with lxml and add to parent element

        sg = Graph()
        sg += what.graph
        for at in what.triples:
            sg += at.graph

        for a in what.type:                
            for b in sg.objects(a, namespaces['rdfs']['isDefinedBy']):
                sg.remove((a, namespaces['rdfs']['isDefinedBy'], b))
            for b in sg.objects(a, namespaces['rdfs']['label']):
                sg.remove((a, namespaces['rdfs']['label'], b))
            sg.remove((what.uri, namespaces['rdf']['type'], a))

        if isinstance(what, Aggregation) or isinstance(what, AggregatedResource):
            # remove atom srlzd bits
            self.remove_link_attrs(sg, what.uri)
            try:
                sg.remove((what.uri, namespaces['dc']['description'], what.description[0]))
            except:
                pass
            for a in what.creator:
                sg.remove((what.uri, namespaces['dcterms']['creator'], a))
            for a in what.contributor:
                sg.remove((what.uri, namespaces['dcterms']['contributor'], a))
            for a in what._ore.similarTo:
                self.remove_link_attrs(sg, a)
                sg.remove((what.uri, namespaces['ore']['similarTo'], a))
            for a in what._ore.aggregates:
                sg.remove((what.uri, namespaces['ore']['aggregates'], a))
            try:
                # aggregation uses dcterms rights, as it's a URI
                for a in what._dcterms.rights:
                    self.remove_link_attrs(sg, a)
                    sg.remove((what.uri, namespaces['dcterms']['rights'], a))
            except:
                pass
            try:
                sg.remove((what.uri, namespaces['foaf']['logo'], what._foaf.logo))
            except:
                pass
            if isinstance(what, Aggregation):
                for a in sg.objects(what.uri, namespaces['ore']['isDescribedBy']):
                    self.remove_link_attrs(sg, a)
                    sg.remove((what.uri, namespaces['ore']['isDescribedBy'], a))
            else:
                # remove isAggregatedBy == rel=related
                for a in what._ore.isAggregatedBy:
                    sg.remove((what.uri, namespaces['ore']['isAggregatedBy'], a))

        elif isinstance(what, ResourceMap):

            self.remove_link_attrs(sg, what.uri)
            for a in what.describes:
                sg.remove((what.uri, namespaces['ore']['describes'], a))
            for a in what.creator:
                sg.remove((what.uri, namespaces['dcterms']['creator'], a))
            try:
                # ReM uses dc rights, as it's a string
                sg.remove((what.uri, namespaces['dc']['rights'], what._dc.rights[0]))
            except:
                pass
            try:
                sg.remove((what.uri, namespaces['dcterms']['modified'], what._dcterms.modified[0]))
            except:
                pass
            try:
                sg.remove((what.uri, namespaces['foaf']['logo'], what._foaf.logo[0]))
            except:
                pass
            try:
                sg.remove((what.uri, namespaces['ore']['describes'], what._ore.describes[0]))
            except:
                pass

        data = sg.serialize(format='xml')
        root = etree.fromstring(data)
        for child in root:
            parent.append(child)

    def make_agent(self, parent, agent):
        n = SubElement(parent, 'name')
        try:
            n.text = str(agent._foaf.name[0])
        except:
            # allow blank names where unknown
            pass

        if agent._foaf.mbox:
            n = SubElement(parent, 'email')
            mb = agent._foaf.mbox[0]
            # Strip mailto: (eg not a URI any more)
            if mb[:7] == "mailto:":
                mb = mb[7:]
            n.text = str(mb)            
        if not isinstance(agent._uri_, BNode):
            n = SubElement(parent, 'uri')
            n.text = str(agent._uri_)

    def make_link(self, parent, rel, t, g):
        e = SubElement(parent, 'link', rel=rel, href=str(t))
        # look for format, language, extent  of t
        fmts = list(g.objects(t, namespaces['dc']['format']))
        if fmts:
            e.set('type', str(fmts[0]))
        langs = list(g.objects(t, namespaces['dc']['language']))
        if langs:
            e.set('hreflang', str(langs[0]))        
        exts = list(g.objects(t, namespaces['dc']['extent']))
        if exts:
            e.set('length', str(exts[0]))
        titls = list(g.objects(t, namespaces['dc']['title']))
        if titls:
            e.set('title', str(titls[0]))

    def serialize(self, rem):
        aggr = rem._aggregation_
        # Check entire graph is connected
        g = self.merge_graphs(rem)
        
        namespaces[''] = namespaces['atom']
        root = Element("feed", nsmap=namespaces)
        namespaces[''] = myNamespace

        ## Aggregation Info
        e = SubElement(root, 'id')
        e.text = str(aggr.uri)
        if not aggr._dc.title:
            raise ValueError("Atom Serialisation requires title on aggregation")
        else:
            e = SubElement(root, 'title')
            e.text = str(aggr._dc.title[0])
        if aggr._dc.description:
            e = SubElement(root, 'subtitle')
            e.text = str(aggr._dc.description[0])

        for who in aggr._dcterms.creator:
            e = SubElement(root, 'author')
            agent = all_objects[who]
            self.make_agent(e, agent)

        for bn in aggr._dcterms.contributor:
            e = SubElement(root, 'contributor')
            agent = all_objects[bn]
            self.make_agent(e, agent)
            
        for t in aggr._ore.similarTo:
            self.make_link(root, 'related', t, g)

        for t in aggr._dcterms.rights:
            self.make_link(root, 'license', t, g)

        for t in aggr._rdf.type:
            e = SubElement(root, 'category', term=str(t))
            try:
                scheme = list(g.objects(t, namespaces['rdfs']['isDefinedBy']))[0]
                e.set('scheme', str(scheme))
            except:
                pass
            try:
                label = list(g.objects(t, namespaces['rdfs']['label']))[0]
                e.set('label', str(label))
            except:
                pass

        orms = []
        for orm in aggr._resourceMaps_:
            if orm != rem:
                self.make_link(root, 'alternate', orm.uri, g)
                orms.append(orm.uri)
        for t in aggr._ore.isDescribedBy:
            # check not in orms
            if not t in orms:
                self.make_link(root, 'alternate', t, g)

        self.generate_rdf(root, aggr, g)

        ## ReM Info
        self.make_link(root, 'self', rem.uri, g)

        e = SubElement(root, 'updated')
        e.text = now()

        # ReM Author
        if rem._dcterms.creator:
            e = SubElement(root, 'generator', uri=str(rem._dcterms.creator[0]))
            agent = all_objects[rem._dcterms.creator[0]]
            e.text = agent._foaf.name[0]

        # if no logo, put in nice ORE icon
        e = SubElement(root, 'icon')
        if aggr._foaf.logo:
            e.text = str(aggr._foaf.logo[0])
        elif rem._foaf.logo:
            e.text = str(rem._foaf.logo[0])
        else:
            e.text = "http://www.openarchives.org/ore/logos/ore_icon.png"
        
        if rem._dc.rights:
            e = SubElement(root, 'rights')
            e.text = rem._dc.rights[0]

        self.generate_rdf(root, rem, g)

        ## Process Entries
        for (res, proxy) in aggr._resources_:
            entry = SubElement(root, 'entry')
            
            e = SubElement(entry, 'id')
            e.text = str(proxy.uri)
            e = SubElement(entry, 'link', rel="alternate", href=str(res.uri))
            # type = dc:format
            fmt = list(g.objects(res.uri, namespaces['dc']['format']))
            if fmt:
                e.set('type', str(fmt[0]))
            
            if not res._dc.title:
                raise ValueError("All entries must have a title for ATOM serialisation")
            else:
                e = SubElement(entry, 'title')
                e.text = str(res._dc.title[0])
            for t in res._rdf.type:
                e = SubElement(entry, 'category', term=str(t))
                try:
                    scheme = list(g.objects(t, namespaces['rdfs']['isDefinedBy']))[0]
                    e.set('scheme', str(scheme))
                except:
                    pass
                try:
                    label = list(g.objects(t, namespaces['rdfs']['label']))[0]
                    e.set('label', str(label))
                except:
                    pass
            for a in res._dcterms.creator:
                e = SubElement(entry, 'author')
                agent = all_objects[a]
                self.make_agent(e, agent)
            for a in res._dcterms.contributor:
                e = SubElement(entry, 'contributor')
                agent = all_objects[a]
                self.make_agent(e, agent)
            if res._dcterms.abstract:
                e = SubElement(entry, 'summary')
                e.text = str(res._dc.description[0])

            # Not sure about this at object level?
            for oa in res._ore.isAggregatedBy:
                if oa != aggr._uri_:
                    e = SubElement(entry, 'link', rel="related", href=str(oa))

            e = SubElement(entry, 'updated')
            e.text = now()

            #e = SubElement(entry, 'updated')
            #e.text = proxy._dcterms.modified[0]
            #if proxy._dc.modified != proxy._dc.created:
            #    e = SubElement(entry, 'published')
            #    e.text = proxy._dcterms.created[0]

            if proxy._ore.lineage:
                e = SubElement(entry, 'link', rel="via", href=str(proxy._ore.lineage[0]))

            self.generate_rdf(entry, res, g)

        data = etree.tostring(root)
        data = data.replace('\n', '')
        data = self.spacesub.sub('', data)
        uri = str(rem._uri_)

        return ReMDocument(uri, data)
