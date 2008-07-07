Foresite
========

Usage
-----

    >>> from foresite.serializer import AtomSerializer

Create an aggregation

    >>> from foresite.ore import Aggregation
    >>> a = Aggregation('my-aggregation-uri')
    >>> a.title = "My Aggregation"

Aggregate two resources

    >>> from foresite.ore import AggregatedResource
    >>> res = AggregatedResource('my-photo-1-uri')
    >>> res.title = "My first photo"
    >>> res2 = AggregatedResource('my-photo-2-uri')
    >>> res2.title = "My second photo"
    >>> a.add_resource(res)
    >>> a.add_resource(res2)

Identify the agent of aggregation

    >>> from foresite.ore import Agent
    >>> me = Agent()
    >>> me.name = "Rob Sanderson"
    >>> a.add_agent(me, 'creator')

Register an Atom serializer with the aggregation

    >>> from foresite.serializer import AtomSerializer
    >>> serializer = AtomSerializer()

    >>> a.register_serialization(serializer, 'my-atom-rem-uri')
    <foresite.ore.ResourceMap object at ...>

    >>> data = a.get_serialization().data
    >>> print data
    <feed ...
