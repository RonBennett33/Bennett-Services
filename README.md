# Bennett-Services
Website and SEO services

## Run locally

The site includes a Java 17 contact server that serves the static files and
submits contact messages through Mailgun. Configure the following environment
variables before starting it:

- `MAILGUN_API_KEY`: a private Mailgun API key
- `MAILGUN_DOMAIN`: the Mailgun sending domain (for example, your sandbox domain)
- `MAILGUN_TO`: the verified inbox that should receive enquiries
- `MAILGUN_FROM` (optional): a sender address authorized for that Mailgun domain
- `PORT` (optional): server port, defaults to `8080`
- `WEB_ROOT` (optional): directory containing `index.html`, defaults to the current directory

Build and run from the repository root:

```sh
mvn -f backend/pom.xml package
MAILGUN_API_KEY=... MAILGUN_DOMAIN=... MAILGUN_TO=... \
  java -jar backend/target/contact-server-1.0.0.jar
```

Open `http://localhost:8080`. In production, keep the API key in the hosting
provider's secret environment configuration and put the Java server behind
HTTPS. The API key is never sent to the browser.
