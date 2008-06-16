/*
 * AtomORESerialiser.java
 *
 * Copyright (c) 2008, Hewlett-Packard Company and Massachusetts
 * Institute of Technology.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * - Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * - Neither the name of the Hewlett-Packard Company nor the name of the
 * Massachusetts Institute of Technology nor the names of their
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDERS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR
 * TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
 * DAMAGE.
 */
package org.dspace.foresite.atom;

import org.dspace.foresite.ORESerialiser;
import org.dspace.foresite.ResourceMapDocument;
import org.dspace.foresite.ResourceMap;
import org.dspace.foresite.ORESerialiserException;
import org.dspace.foresite.Aggregation;
import org.dspace.foresite.OREException;
import org.dspace.foresite.ReMSerialisation;
import org.dspace.foresite.Triple;
import org.dspace.foresite.Agent;
import org.dspace.foresite.AggregatedResource;
import org.dspace.foresite.Proxy;
import org.dspace.foresite.ORESerialiserFactory;
import org.dspace.foresite.jena.AggregationJena;
import org.jdom.Element;
import org.jdom.Namespace;
import org.jdom.JDOMException;
import org.jdom.Document;
import org.jdom.input.SAXBuilder;

import java.util.Properties;
import java.util.List;
import java.util.ArrayList;
import java.util.Date;
import java.net.URI;
import java.io.StringWriter;
import java.io.IOException;
import java.io.ByteArrayInputStream;

import com.sun.syndication.feed.atom.Feed;
import com.sun.syndication.feed.atom.Link;
import com.sun.syndication.feed.atom.Generator;
import com.sun.syndication.feed.atom.Entry;
import com.sun.syndication.feed.atom.Category;
import com.sun.syndication.feed.atom.Person;
import com.sun.syndication.io.WireFeedOutput;
import com.sun.syndication.io.FeedException;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Resource;

/**
 * @Author Richard Jones
 */
public class AtomORESerialiser implements ORESerialiser
{
	public static Namespace rdfNS = Namespace.getNamespace("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");

	public void configure(Properties properties)
    {
        
    }

	public ResourceMapDocument serialiseRaw(ResourceMap rem) throws ORESerialiserException
	{
		return null;
	}

	public ResourceMapDocument serialise(ResourceMap resourceMap)
			throws ORESerialiserException
	{
		try
		{
			// create a copy of the resource map to work on
			ResourceMap rem = resourceMap.copy();

			// The method we are going to use is to de-construct the resource
			// map as we turn it into an atom feed
			/////////////////////////////////////////////////////////////////

			// get the relevant info from the REM
			URI uri_r = rem.getURI();
			Aggregation agg = rem.getAggregation();
			URI uri_a = agg.getURI();
			List<URI> similarTo = agg.getSimilarTo();
			List<ReMSerialisation> otherRems = agg.getReMSerialisations();
			List<AggregatedResource> ars = agg.getAggregatedResources();

			// build the ATOM objects
			Feed atom = new Feed("atom_1.0");
			List<Entry> entries = new ArrayList<Entry>();
			List<Link> relateds = new ArrayList<Link>();
			List<Link> alternates = new ArrayList<Link>();
			List<Element> foreign = new ArrayList<Element>();
			List<Category> cats = new ArrayList<Category>();

			// Do the cross-walk
			////////////////////

			// atom:id :: Aggregation URI
			atom.setId(uri_a.toString());

			// atom:link@rel=related :: ore:similarTo (& rdfs:seeAlso)
			for (URI similar : similarTo)
			{
				// create the link
				Link link = new Link();
				link.setRel("related");
				link.setHref(similar.toString());
				relateds.add(link);

				// FIXME: need to include the following information
				// @type = URI-similar dc:format "mime"
				// @hreflang = URI-similar dc:language "lang"
				// @title = URI-similar dc:title "title"

				// remove these links from the resource map
				agg.clearSimilarTo();
			}

			// atom:link@rel=alternate :: ore:isDescribedBy for other Resource Maps
			for (ReMSerialisation serial : otherRems)
			{
				if (!serial.getURI().equals(uri_r))
				{
					Link link = new Link();
					link.setRel("alternate");
					link.setHref(serial.getURI().toString());
					alternates.add(link);

					// FIXME: need to include the following information
					// @type = URI-alt dc:format "mime"
					// @hreflang = URI-alt dc:language "lang"
					// @title = URI-alt dc:title "title"
				}

				// clear the list of rem serialisations
				agg.clearReMSerialisations();
			}

			// FIXME: the rights are not URIs they are strings!  Do we need to change the model?
			// atom:link@rel=license :: rights URI
			// agg.getRights();

			// atom:link@rel=self :: REM URI
			Link self = new Link();
			self.setRel("self");
			self.setHref(uri_r.toString());
			relateds.add(self);

			// atom:icon :: URI-A foaf:logo URI-icon
			// FIXME: for the time being just use the default icon from openarchives
			atom.setIcon("http://www.openarchives.org/ore/favicon.ico");

			// atom:generator :: REM creator (arbitrary)
			// all creators will be duplicated when serialised as RDF
			List<Agent> remCreators = rem.getCreators();
			if (remCreators != null && !remCreators.isEmpty())
			{
				Agent creator = remCreators.get(0);
				Generator generator = new Generator();
				List<String> names = creator.getNames();
				URI creatorURI = creator.getURI();

				StringBuilder sb = new StringBuilder();
				for (String name : names)
				{
					sb.append(name + " ");
				}
				generator.setValue(sb.toString());

				if (creatorURI != null)
				{
					generator.setUrl(creatorURI.toString());
				}

				atom.setGenerator(generator);
			}

			// FIXME: don't have the facilities in the model yet to handle this information
			// atom:contributor :: URI-A dcterms:contributor

			// FIXME: implement this based on triple selector
			// atom:subtitle :: URI-A dc:description "subtitle"

			// atom:updated :: REM modified date
			Date modified = rem.getModified();
			atom.setUpdated(modified);
			rem.removeModified(); // clear the modification date for later RDF serialisation

			// atom:rights :: REM Rights
			String rights = rem.getRights();
			atom.setRights(rights);
			rem.removeRights(); // clear the rights for later RDF serialisation

			// atom:author :: Aggregation creators
			List<Agent> agents = agg.getCreators();
			List<Person> authors = new ArrayList<Person>();
			for (Agent agent : agents)
			{
				Person author = new Person();
				List<String> names = agent.getNames();
				List<URI> mboxes = agent.getMboxes();
				URI uri = agent.getURI();

				// FIXME: data loss in authors with multiple name entries
				for (String name : names)
				{
					author.setName(name);
				}

				// FIXME: data loss in authors with multiple name entries
				for (URI mbox : mboxes)
				{
					author.setEmail(mbox.toString());
				}

				if (uri != null)
				{
					author.setUri(uri.toString());
				}

				authors.add(author);
			}
			atom.setAuthors(authors);
			agg.clearCreators(); // remove the creators for later RDF serialisation

			// atom:category :: all rdf:type elements (Aggregation type mandatory)
			List<URI> types = agg.getTypes();
			for (URI type : types)
			{
				Category category = new Category();
				category.setTerm(type.toString());
				category.setScheme("http://www.openarchives.org/ore/terms/");
				// FIXME: this does not take into account relations of the form: URI-A rdfs:label "label"
				if (type.toString().equals("http://www.openarchives.org/ore/terms/Aggregation"))
				{
					category.setLabel("Aggregation");
				}
				cats.add(category);
			}
			agg.clearTypes();  // clear the types in preparation for RDF serialisation
			atom.setCategories(cats);

			// atom:title :: Aggregation title if exists
			List<String> titles = agg.getTitles();
			if (titles != null && !titles.isEmpty())
			{
				atom.setTitle(titles.get(0));

				// remove the used title, but leave the others to be RDF serialised
				titles.remove(0);
				agg.setTitles(titles);
			}

			// atom:entry :: Aggregated Resource
			for (AggregatedResource ar : ars)
			{
				// generate the entry
				Entry entry = this.atomEntry(ar);

				// add the entry to the list of entries
				entries.add(entry);
			}

			// clear the aggregated resources before building the RDF
			agg.clearAggregatedResources();

			// We do the remaining RDF stuff last
			/////////////////////////////////////

			/*
			// rdf:Description about=Aggregation URI :: all remaining RDF from the Aggregation
			List<Element> aggElements = this.getAggregationRDF(agg);
			foreign.addAll(aggElements);

			// rdf:Description about=REM URI :: all remaining RDF from the REM
			List<Element> remElements = this.getReMRDF(rem);
			foreign.addAll(remElements);
			*/

			// rdf:Description about=URI-R|URI-A|URI-other as RDF
			List<Element> rdf = this.getTopLevelRDF(rem);
			if (rdf != null)
			{
				atom.setForeignMarkup(rdf);
			}

			// assemble all the lists into the ATOM document
			atom.setOtherLinks(relateds);
			atom.setAlternateLinks(alternates);
			atom.setEntries(entries);

			// write the ATOM feed document to a string
			StringWriter writer = new StringWriter();
			WireFeedOutput output = new WireFeedOutput();
			output.output(atom, writer);

			// build and return the resource map document
			ResourceMapDocument rmd = new ResourceMapDocument();
			rmd.setSerialisation(writer.toString());
			rmd.setMimeType("application/atom+xml");
			rmd.setUri(uri_r);

			return rmd;
		}
		catch (OREException e)
		{
			throw new ORESerialiserException(e);
		}
		catch(IOException e)
		{
			throw new ORESerialiserException(e);
		}
		catch (FeedException e)
		{
			throw new ORESerialiserException(e);
		}
	}

	private List<Element> getAggregationRDF(Aggregation agg)
			throws OREException
	{
		Element aggElement = new Element("Description", rdfNS);
		aggElement.setAttribute("about", agg.getURI().toString());

		List<Triple> triples = agg.listTriples();

		// create a new Jena model
		Model model = ModelFactory.createDefaultModel();
		Resource resource = model.createResource(agg.getURI().toString());

		Model original = ((AggregationJena) agg).getModel();


		// iterate the triples, filtering out valid atom elements

		/*
			Element aggElement = new Element("Description", rdfNS);
			aggElement.setAttribute("about", agg.getURI().toString());

			List<Triple> aggTriples = agg.listTriples();

			List<Element> seeAlsos = this.jdomSeeAlso(seeAlso);
			List<Element> otherTriples = this.jdomOtherTriples(aggTriples);

			this.addFromAtomNamespace(aggTriples);

			for (Triple triple : aggTriples)
			{
				// atom:**** :: anything in the atom: namespace

				// all other things are straight RDF

				// FIXME: we need to filter this list of triples to not insert duplicate
				// information.  Not hard, just boring, do it later ;)
			}

			// FIXME: this actually takes an argument of List<Element>
			// aggElements.add(aggElement);
			// atom.setForeignMarkup(aggTriples);
			*/
		return null;
	}

	private List<Element> getReMRDF(ResourceMap rem)
			throws OREException
	{
		List<Triple> remTriples = rem.listTriples();
		Element remElement = new Element("Description", rdfNS);
		remElement.setAttribute("about", rem.getURI().toString());
		for (Triple triple : remTriples)
		{
			// atom:**** :: anything in the atom: namespace

			// all other things are straight RDF

			// FIXME: we need to filter this list of triples to not insert duplicate
			// information.  Not hard, just boring, do it later ;)
		}
		return null;
	}

	private List<Element> getTopLevelRDF(ResourceMap rem)
			throws OREException
	{
		try
		{
			// first generate the remaining RDF as XML
			ORESerialiser serial = ORESerialiserFactory.getInstance("RDF/XML");
			ResourceMapDocument rmd = serial.serialiseRaw(rem);
			String rdfxml = rmd.getSerialisation();

			// now read the XML into a JDOM document
			ByteArrayInputStream is = new ByteArrayInputStream(rdfxml.getBytes());
			SAXBuilder builder = new SAXBuilder();
			Document document = builder.build(is);

			// now get the element list out of the document
			Element root = document.getRootElement();
			List<Element> children = root.getChildren();

			// detach all the child elements (have to do this without an iterator!)
			// otherwise the parent link for the children screws with the JDOM model
			// which underlies ROME
			List<Element> detached = new ArrayList<Element>();
			for (int i = 0; i < children.size(); i++)
			{
				detached.add((Element) children.get(i).detach());
			}

			return detached;
		}
		catch (ORESerialiserException e)
		{
			throw new OREException(e);
		}
		catch (JDOMException e)
		{
			throw new OREException(e);
		}
		catch (IOException e)
		{
			throw new OREException(e);
		}
	}

	private List<Element> getEntryRDF(AggregatedResource ar)
			throws OREException
	{
		// FIXNE: In order to do this we need to include some tools in the model which will
		// allow us to detach graph segments and serialise them independently.

		/*
		List<Triple> triples = ar.listTriples();
		Element arElement = new Element("Description", rdfNS);
		arElement.setAttribute("about", ar.getURI().toString());
		for (Triple triple : arTriples)
		{
			// atom:entry/atom:**** :: anything in the atom: namespace

			// all other things are straight RDF

			// FIXME: we need to filter this list of triples to not insert duplicate
			// information.  Not hard, just boring, do it later ;)
		}*/
		return null;
	}

	private Entry atomEntry(AggregatedResource ar)
			throws OREException
	{
		// build the entry elements
		Entry entry = new Entry();
		List<Link> entryAlt = new ArrayList<Link>();
		List<Link> entryOther = new ArrayList<Link>();
		List<Category> categories = new ArrayList<Category>();

		// atom:entry/atom:id :: proxy URI if exists
		// atom:entry/atom:link@rel="via" :: Proxy Lineage URI if exists
		Proxy proxy = ar.getProxy();
		if (proxy != null)
		{
			entry.setId(proxy.getURI().toString());

			URI lineage = proxy.getLineage();
			if (lineage != null)
			{
				Link link = new Link();
				link.setRel("via");
				link.setHref(lineage.toString());
				entryOther.add(link);

				// FIXME: needs to implement the following
				// @type: URI-P-other dc:format "mime"
				// @hreflang: URI-P-other dc:language "lang"
				// @title: URI-P-other dc:title "title"
			}

		}

		// atom:entry/atom:link@rel="alternate" :: Aggregated Resource URI
		Link alt = new Link();
		alt.setRel("alternate");
		alt.setHref(ar.getURI().toString());
		entryAlt.add(alt);

		// FIXME: need to add the following
		// @type: URI-AR dc:format "mime"
		// @hreflang: URI-AR dc:language "lang"
		// @title: URI-AR dc:title "title"
		// @title: URI-AR dcterms:extent "length"

		// atom:author :: Aggregation creators
		List<Agent> agents = ar.getCreators();
		List<Person> authors = new ArrayList<Person>();
		if (agents != null)
		{
			for (Agent agent : agents)
			{
				Person author = new Person();
				List<String> names = agent.getNames();
				List<URI> mboxes = agent.getMboxes();
				URI uri = agent.getURI();

				// FIXME: data loss in authors with multiple name entries
				for (String name : names)
				{
					author.setName(name);
				}

				// FIXME: data loss in authors with multiple name entries
				for (URI mbox : mboxes)
				{
					author.setEmail(mbox.toString());
				}

				author.setUri(uri.toString());

				authors.add(author);
			}
		}
		entry.setAuthors(authors);
		ar.clearCreators(); // remove the creators for later RDF serialisation

		// FIXME: implement using triple selector
		// atom:entry/atom:title :: URI-AR dc:title "title"

		// atom:entry/atom:category :: URI-AR rdf:type URI-term
		List<URI> types = ar.getTypes();
		for (URI type : types)
		{
			Category category = new Category();
			category.setTerm(type.toString());
			// FIXME: does not take into account rdfs:isDefinedBy
			// FIXME: does not take into account rdfs:label
			categories.add(category);
		}
		entry.setCategories(categories);

		// FIXME: don't have this implemented in the model yet
		// atom:entry/atom:contributor

		// FIXME: implement with triple selector
		// atom:entry/atom:summary :: URI-AR dcterms:abstract "summary"

		// atom:entry/atom:link@rel="related" :: Other Aggregation URIs for this Aggregated Resource
		List<URI> otherAggs = ar.getAggregations();
		for (URI other : otherAggs)
		{
			Link link = new Link();
			link.setRel("related");
			link.setHref(other.toString());
			entryOther.add(link);

			// FIXME: needs also to support:
			// @hreflang: URI-A-other dc:language "lang"
			// @title: URI-A-other dc:title "title"
		}

		// rdf:Description about=URI-AR|URI-P|URI-other-connected :: all remaining RDF from the aggregated resource
		List<Element> arElements = this.getEntryRDF(ar);

		// add the various lists to the entry
		entry.setAlternateLinks(entryAlt);
		entry.setOtherLinks(entryOther);
		entry.setCategories(categories);
		entry.setForeignMarkup(arElements);

		return entry;
	}
}
