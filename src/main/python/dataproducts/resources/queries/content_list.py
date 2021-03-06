def init():
    return """
    {
        "queryType": "select",
        "dataSource": "content-model-snapshot",
        "filter": {
            "type": "and",
            "fields": [
                {
                    "type": "selector",
                    "dimension": "objectType",
                    "value": "Content"
                },
                {
                    "type": "selector",
                    "dimension": "contentType",
                    "value": "Resource"
                },
                {
                    "type": "selector",
                    "dimension": "status",
                    "value": "Live"
                }
            ]
        },
        "aggregations": [],
        "granularity": "all",
        "postAggregations": [],
        "intervals": "1901-01-01T00:00:00+00:00/2101-01-01T00:00:00+00:00",
        "dimensions": [
            "identifier",
            "board",
            "medium",
            "gradeLevel",
            "subject",
            "name",
            "channel",
            "contentType",
            "mediaType",
            "mimeType",
            "objectType",
            "resourceType",
            "status",
            "author",
            "creator",
            "createdOn",
            "lastPublishedOn",
            "lastUpdatedOn",
            "me_averageRating",
            "me_totalRatings"
        ],
        "metrics": [
            ""
        ],
        "pagingSpec": {
            "pagingIdentifiers": {},
            "threshold": 10000
        }
    }
    """