# [OpenCompany](https://github.com/open-company) Slack Router Service

[![MPL License](http://img.shields.io/badge/license-MPL-blue.svg?style=flat)](https://www.mozilla.org/MPL/2.0/)
[![Build Status](https://travis-ci.org/open-company/open-company-slack-router.svg)](https://travis-ci.org/open-company/open-company-slack-router)
[![Dependencies Status](https://versions.deps.co/open-company/open-company-slack-router/status.svg)](https://versions.deps.co/open-company/open-company-slack-router)

## Background

> I've come to learn there is a virtuous cycle to transparency and a very vicious cycle of obfuscation.

> -- [Jeff Weiner](https://www.linkedin.com/in/jeffweiner08)

Companies struggle to keep everyone on the same page. People are hyper-connected in the moment but still don’t know what’s happening across the company. Employees and investors, co-founders and execs, customers and community, they all want more transparency. The solution is surprisingly simple and effective - great company updates that build transparency and alignment.

With that in mind we designed the [Carrot](https://carrot.io/) software-as-a-service application, powered by the open source [OpenCompany platform](https://github.com/open-company). The product design is based on three principles:

1. It has to be easy or no one will play.
2. The "big picture" should always be visible.
3. Alignment is valuable beyond the team, too.

Carrot simplifies how key business information is shared with stakeholders to create alignment. When information about growth, finances, ownership and challenges is shared transparently, it inspires trust, new ideas and new levels of stakeholder engagement. Carrot makes it easy for founders to engage with employees and investors, creating alignment for everyone.

[Carrot](https://carrot.io/) is GitHub for the rest of your company.

Transparency expectations are changing. Organizations need to change as well if they are going to attract and retain savvy employees and investors. Just as open source changed the way we build software, transparency changes how we build successful companies with information that is open, interactive, and always accessible. Carrot turns transparency into a competitive advantage.

To get started, head to: [Carrot](https://carrot.io/)


## Overview

The OpenCompany Slack Router service handles incoming events from the Slack API. It will then route or query our other services to handle the incoming event.


## Local Setup

Prospective users of [Carrot](https://carrot.io/) should get started by going to [Carrot.io](https://carrot.io/). The following local setup is **for developers** wanting to work on the OpenCompany Slack Router Service.

Most of the dependencies are internal, meaning [Leiningen](https://github.com/technomancy/leiningen) will handle getting them for you. There are a few exceptions:

* [Java 8](http://www.oracle.com/technetwork/java/javase/downloads/index.html) - a Java 8 JRE is needed to run Clojure
* [Leiningen](https://github.com/technomancy/leiningen) - Clojure's build and dependency management tool
* [ngrok](https://ngrok.com/) - Secure web tunnel to localhost

#### Java

Chances are your system already has Java 8+ installed. You can verify this with:

```console
java -version
```

If you do not have Java 8+ [download it](http://www.oracle.com/technetwork/java/javase/downloads/index.html) and follow the installation instructions.

#### Leiningen

Leiningen is easy to install:

1. Download the latest [lein script from the stable branch](https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein).
1. Place it somewhere that's on your $PATH (`env | grep PATH`). `/usr/local/bin` is a good choice if it is on your PATH.
1. Set it to be executable. `chmod 755 /usr/local/bin/lein`
1. Run it: `lein` This will finish the installation.

Then let Leiningen install the rest of the dependencies:

```console
git clone https://github.com/open-company/open-company-slack-router.git
cd open-company-slack-router
lein deps
```

#### Required Secrets

A secret is shared between the [Slack Router service](https://github.com/open-company/open-company-slack-router) and the Auth service for creating and validating [JSON Web Tokens](https://jwt.io/).

A [Slack App](https://api.slack.com/apps) needs to be created for OAuth authentication and events. For local development, create a Slack app with a Redirect URI of `http://localhost:3003/slack-oauth` and get the client ID and secret from the Slack app you create.  From the /apps url you will be able to chose 'Event Subscriptions' and then turn on the 'Enable Events' toggle.  Once this is turned on the router will begin receiving events from Slack.

An [AWS SNS](https://aws.amazon.com/sns/) pub/sub topic is used to push slack events to interested listeners. To take advantage of this capability, configure the `aws-sns-slack-topic-arn` with the ARN (Amazon Resource Name) of the SNS topic you setup in AWS.

Make sure you update the `CHANGE-ME` items in the section of the `project.clj` that looks like this to contain your actual JWT, Slack, and AWS secrets:

```clojure
;; Dev environment and dependencies
:dev [:qa {
  :env ^:replace {
    :liberator-trace "true" ; liberator debug data in HTTP response headers
    :open-company-auth-passphrase "this_is_a_dev_secret" ; JWT secret
    :hot-reload "true" ; reload code when changed on the file system
    :open-company-slack-client-id "CHANGE-ME"
    :open-company-slack-client-secret "CHANGE-ME"
    :aws-access-key-id "CHANGE-ME"
    :aws-secret-access-key "CHANGE-ME"
    :aws-sns-slack-topic-arn "" ; SNS topic to publish notifications (optional)
    :log-level "debug"
  }
```

You can also override these settings with environmental variables in the form of `OPEN_COMPANY_AUTH_PASSPHRASE` and
`AWS_ACCESS_KEY_ID`, etc. Use environmental variables to provide production secrets when running in production.

#### Slack proxy (ngrok)

ngrok allows you to setup a secure web tunnel for HTTP/S requests to your
localhost. You'll need this to utilize the Slack webhook during local
development so Slack can communicate with your local development environment.

ngrok is trivial to setup:

1. [Download](https://ngrok.com/download) the version for your operating system.
1. Unzip the download and put ngrok someplace handy for you (in your path is good!)
1. Verify you can run ngrok with: `ngrok help`

To use the webhook from Slack with local development, you need to run ngrok, then configure your Slack integration.

First start the Slack Router Service (see below), and start the ngrok tunnel:

```console
ngrok http 3009
```

Note the URL ngrok provides. It will look like: `http://6ae20d9b.ngrok.io` -> localhost:3009

To configure the Slack to use the ngrok tunnel as the destination of link_shared events. Go to
[Your Apps](https://api.slack.com/apps) and click the "Carrot (Local Development)" app.

Click the "Event Subscriptions" navigation in the menu. Click the toggle on.

Add the URL provided by ngrok above, modifying `http` to `https` and with a `/slack/unfurl` suffix,
e.g. `https://6ae20d9b.ngrok.io/slack/unfurl`

 Click the "Add Team Event" button and add the `link_shared` event. Click the "Add Bot User Event" button and
 add the `link_shared` event.

Click the "Save Changes" button.

You will need to add domains to the slack app configuration.
`localhost` for local dev
`staging.carrot.io` for staging
`beta.carrot.io` for beta

NB: Make sure when you are done testing locally, you disable the "Enable Events" toggle so Slack will stop trying
to echo events to your local environment via ngrok.

To receive events from the SNS topic with SQS, you will need to subscribe an SQS queue to the topic.

In AWS go to the SQS service console. Click the SQS queue you created. From the
'Queue Actions' menu, chose 'Subscribe Queue to SNS topic'.

## Usage

Prospective users of [Carrot](https://carrot.io/) should get started by going to [Carrot.io](https://carrot.io/). The following usage is **for developers** wanting to work on the OpenCompany Slack Router Service.

**Make sure you've updated `project.clj` as described above.**

To start a production instance:

```console
lein start!
```

Or to start a development instance:

```console
lein start
```

To clean all compiled files:

```console
lein clean
```

To create a production build run:

```console
lein build
```

## Testing

Tests are run in continuous integration of the `master` and `mainline` branches on [Travis CI](https://travis-ci.org/open-company/open-company-slack-router):

[![Build Status](http://img.shields.io/travis/open-company/open-company-slack-router.svg?style=flat)](https://travis-ci.org/open-company/open-company-slack-router)

To run the tests locally:

```console
lein test!
```

## Technical Design

The Slack Router service current has two responsibilities:

- Process unfurl requests
- Post Slack message events to an SNS topic.



## Participation

Please note that this project is released with a [Contributor Code of Conduct](https://github.com/open-company/open-company-slack-router/blob/mainline/CODE-OF-CONDUCT.md). By participating in this project you agree to abide by its terms.


## License

Distributed under the [Mozilla Public License v2.0](http://www.mozilla.org/MPL/2.0/).

Copyright © 2015-2018 OpenCompany, LLC
