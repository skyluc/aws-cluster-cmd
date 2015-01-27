package org.skyluc.aws.model

import com.amazonaws.services.ec2.model.{ Image => AImage }

case class Image(image: AImage)