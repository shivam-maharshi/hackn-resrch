# Docker
sudo docker run -i --net=host -p 8080:8080 docker://shivammaharshi/mediawiki /bin/bash
# rkt
./rkt run --interactive=true --net=host --insecure-options=image docker://shivammaharshi/mediawiki --exec /bin/bash
